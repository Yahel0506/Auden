import type { DownloadContext, DownloadResult } from './download';
import type { FileStore } from './files';
import type { ChangeBus, OfflineStore } from './store';
import type { OfflineItem, OfflineJob, OfflineSong, PauseReason, PausedState } from './types';

/**
 * The offline queue: Android's QueueStore + Downloads retry rules, over IndexedDB.
 *
 *  - Jobs live in the store, so a closed tab loses nothing; a job that was running comes back queued.
 *  - Up to [CONCURRENCY] transfers, but their player requests are spaced [SPACING_MS] apart: a bulk album must not
 *    look like a burst to YouTube.
 *  - A failure worth retrying backs off 5 s, 15 s, 45 s (persisted as `notBefore`) and then stops as "failed" with
 *    the reason; one that will read the same next time (no downloadable audio, a removed track) fails at once.
 *  - Failures that are about the *queue*, not the song, pause everything instead of failing the rest: no
 *    connection, no room, and YouTube's bot check (which reads as an age gate). The song stays queued.
 *  - One tab runs it (a Web Lock in runtime.ts); the others only write the store and tell the owner.
 */

export const CONCURRENCY = 2;
export const SPACING_MS = 3_000;
export const BACKOFF_MS = [5_000, 15_000, 45_000] as const;
export const MAX_RETRIES = BACKOFF_MS.length;

const VIDEO_ID = /^[A-Za-z0-9_-]{11}$/;

export type Failure =
  | { kind: 'abort' }
  | { kind: 'pause'; reason: PauseReason }
  | { kind: 'permanent'; message: string }
  | { kind: 'retry'; message: string };

const messageOf = (error: unknown) => (error instanceof Error ? error.message : String(error));

/** What a failed attempt means for the job and the queue. Duck-typed on `name`/`kind`, so it needs no YouTube code. */
export function classify(error: unknown, online: boolean): Failure {
  const e = error as { name?: string; kind?: string; status?: number; ageGated?: boolean } | null;
  if (e?.name === 'AbortError') return { kind: 'abort' };
  if (e?.name === 'QuotaExceededError') return { kind: 'pause', reason: 'storage-full' };
  if (e?.name === 'UnplayableError') {
    return e.ageGated ? { kind: 'pause', reason: 'verify' } : { kind: 'permanent', message: messageOf(error) };
  }
  if (e?.name === 'AudioStreamError') {
    if (e.kind === 'sabr') return { kind: 'permanent', message: 'YouTube only streams this track; there is no audio file to keep.' };
    if (e.kind === 'webpo') return { kind: 'permanent', message: 'YouTube would only serve this track to a web client, which cuts off after about a minute. Try again later.' };
    if (e.kind === 'http' && (e.status === 404 || e.status === 410)) return { kind: 'permanent', message: 'YouTube no longer has this audio.' };
  }
  if (!online) return { kind: 'pause', reason: 'offline' };
  return { kind: 'retry', message: messageOf(error) };
}

export type RowStatus = 'active' | 'queued' | 'waiting' | 'failed';

export interface QueueRow {
  job: OfflineJob;
  status: RowStatus;
  done: number;
  total: number;
}

export interface QueueView {
  paused: PausedState | null;
  rows: QueueRow[];
}

export interface QueueDeps {
  store: OfflineStore;
  files: FileStore;
  bus: ChangeBus;
  download: (song: OfflineSong, ctx: DownloadContext) => Promise<DownloadResult>;
  /** The song's cover as a blob, or null when the browser won't hand it over. Never fails the job. */
  fetchCover?: (url: string) => Promise<Blob | null>;
  /** Called with each song that finished (lyrics are looked up from here). */
  onSaved?: (item: OfflineItem) => void;
  now?: () => number;
  online?: () => boolean;
  setTimer?: (fn: () => void, ms: number) => unknown;
  clearTimer?: (handle: unknown) => void;
  concurrency?: number;
  spacingMs?: number;
}

interface Running {
  controller: AbortController;
  done: number;
  total: number;
}

export class OfflineQueue {
  private jobs = new Map<string, OfflineJob>();
  private paused: PausedState | null = null;
  private readonly running = new Map<string, Running>();
  private readonly listeners = new Set<() => void>();
  private owner = false;
  private loaded = false;
  private lastStart = -Infinity;
  private wake: unknown = null;
  private readonly now: () => number;
  private readonly online: () => boolean;
  private readonly setTimer: (fn: () => void, ms: number) => unknown;
  private readonly clearTimer: (handle: unknown) => void;
  private readonly concurrency: number;
  private readonly spacing: number;

  constructor(private readonly deps: QueueDeps) {
    this.now = deps.now ?? Date.now;
    this.online = deps.online ?? (() => (typeof navigator === 'undefined' ? true : navigator.onLine));
    this.setTimer = deps.setTimer ?? ((fn, ms) => setTimeout(fn, ms));
    this.clearTimer = deps.clearTimer ?? ((h) => clearTimeout(h as ReturnType<typeof setTimeout>));
    this.concurrency = deps.concurrency ?? CONCURRENCY;
    this.spacing = deps.spacingMs ?? SPACING_MS;
    deps.bus.subscribe(() => void this.reload());
  }

  // ---- State ---------------------------------------------------------------------------

  /** Reads the store; safe to call again (another tab changed something). */
  private reloadSeq = 0;

  async reload(): Promise<void> {
    const seq = ++this.reloadSeq;
    const [stored, paused] = await Promise.all([this.deps.store.jobs(), this.deps.store.paused()]);
    if (seq !== this.reloadSeq) return; // a newer read is on its way
    const next = new Map(stored.map((j) => [j.videoId, j]));
    for (const [id, run] of this.running) if (!next.has(id)) run.controller.abort();
    this.jobs = next;
    this.paused = paused;
    if (paused) for (const run of this.running.values()) run.controller.abort();
    this.loaded = true;
    this.changed();
    this.pump();
  }

  view(): QueueView {
    const now = this.now();
    const rows = [...this.jobs.values()]
      .sort((a, b) => a.addedAt - b.addedAt)
      .map((job): QueueRow => {
        const run = this.running.get(job.videoId);
        const status: RowStatus = run ? 'active' : job.state === 'failed' ? 'failed' : job.notBefore > now ? 'waiting' : 'queued';
        return { job, status, done: run?.done ?? 0, total: run?.total ?? 0 };
      });
    return { paused: this.paused, rows };
  }

  subscribe(listener: () => void): () => void {
    this.listeners.add(listener);
    return () => void this.listeners.delete(listener);
  }

  private changed() {
    this.listeners.forEach((l) => l());
  }

  /** Marks this tab as the one that runs transfers (or not), and starts or stops them. */
  setOwner(owner: boolean) {
    this.owner = owner;
    if (!owner) {
      for (const run of this.running.values()) run.controller.abort();
      this.clearWake();
    } else this.pump();
  }

  // ---- Commands ------------------------------------------------------------------------

  /** Queues the songs that are real YouTube songs and not already kept or queued. */
  async enqueue(songs: OfflineSong[], { toFolder = false }: { toFolder?: boolean } = {}): Promise<{ added: number; skipped: number }> {
    if (!this.loaded) await this.reload();
    const fresh: OfflineJob[] = [];
    const revived: OfflineJob[] = [];
    const seen = new Set<string>();
    let skipped = 0;
    const kept = new Set((await this.deps.store.items()).map((i) => i.videoId));
    for (const song of songs) {
      if (!VIDEO_ID.test(song.videoId) || seen.has(song.videoId) || kept.has(song.videoId)) {
        skipped++;
        continue;
      }
      seen.add(song.videoId);
      const existing = this.jobs.get(song.videoId);
      if (existing && existing.state !== 'failed') {
        // Already on its way: it only picks up the wish to land in the folder too.
        if (toFolder && !existing.toFolder) revived.push({ ...existing, toFolder: true });
        else skipped++;
        continue;
      }
      const job: OfflineJob = { ...song, state: 'queued', attempt: 0, notBefore: 0, reason: null, addedAt: existing?.addedAt ?? this.now(), ...(toFolder ? { toFolder: true } : {}) };
      (existing ? revived : fresh).push(job);
    }
    const all = [...fresh, ...revived];
    if (all.length) {
      await this.deps.store.putJobs(all);
      this.deps.bus.emit();
    }
    return { added: all.length, skipped };
  }

  async pause(reason: PauseReason = 'user') {
    await this.setPaused({ reason });
  }

  async resume() {
    if (!this.paused) return;
    await this.setPaused(null);
  }

  private async setPaused(state: PausedState | null) {
    this.paused = state;
    await this.deps.store.setPaused(state);
    if (state) for (const run of this.running.values()) run.controller.abort();
    this.deps.bus.emit();
  }

  async cancel(videoId: string) {
    this.running.get(videoId)?.controller.abort();
    await this.deps.store.deleteJob(videoId);
    this.deps.bus.emit();
  }

  async retry(videoIds?: string[]) {
    const ids = new Set(videoIds ?? [...this.jobs.values()].filter((j) => j.state === 'failed').map((j) => j.videoId));
    const revived = [...ids].map((id) => this.jobs.get(id)).filter((j): j is OfflineJob => !!j && j.state === 'failed').map((j): OfflineJob => ({ ...j, state: 'queued', attempt: 0, notBefore: 0, reason: null }));
    if (!revived.length) return;
    await this.deps.store.putJobs(revived);
    this.deps.bus.emit();
  }

  async clearFailed() {
    const ids = [...this.jobs.values()].filter((j) => j.state === 'failed').map((j) => j.videoId);
    if (!ids.length) return;
    await this.deps.store.deleteJobs(ids);
    this.deps.bus.emit();
  }

  /** Deletes finished copies: the file first, then the record, so a failure leaves a row the audit can mend. */
  async removeItems(videoIds: string[]) {
    for (const id of videoIds) {
      const item = await this.deps.store.item(id);
      if (item) await this.deps.files.remove(item.file);
      await this.deps.store.deleteItem(id);
    }
    this.deps.bus.emit();
  }

  /** The browser came back online: a queue paused for want of a connection carries on. */
  async onOnline() {
    if (this.paused?.reason === 'offline') await this.resume();
  }

  // ---- The pump ------------------------------------------------------------------------

  private clearWake() {
    if (this.wake !== null) this.clearTimer(this.wake);
    this.wake = null;
  }

  private schedule(at: number) {
    this.clearWake();
    this.wake = this.setTimer(() => {
      this.wake = null;
      this.pump();
    }, Math.max(0, at - this.now()));
  }

  private pump() {
    if (!this.owner || !this.loaded || this.paused) return;
    this.clearWake();
    let nextAt = Infinity;
    const ready = [...this.jobs.values()]
      .filter((j) => j.state === 'queued' && !this.running.has(j.videoId))
      .sort((a, b) => a.addedAt - b.addedAt);
    for (const job of ready) {
      if (this.running.size >= this.concurrency) break;
      if (job.notBefore > this.now()) {
        nextAt = Math.min(nextAt, job.notBefore);
        continue;
      }
      const startAt = this.lastStart + this.spacing;
      if (startAt > this.now()) {
        nextAt = Math.min(nextAt, startAt);
        break;
      }
      this.lastStart = this.now();
      void this.run(job);
    }
    if (nextAt !== Infinity) this.schedule(nextAt);
  }

  private async run(job: OfflineJob) {
    const state: Running = { controller: new AbortController(), done: 0, total: 0 };
    this.running.set(job.videoId, state);
    this.changed();
    let wroteFile: string | null = null;
    try {
      const result = await this.deps.download(job, {
        signal: state.controller.signal,
        progress: (done, total) => {
          state.done = done;
          state.total = total;
          this.changed();
        },
      });
      wroteFile = result.file;
      if (state.controller.signal.aborted) throw new DOMException('Aborted', 'AbortError');
      const cover = job.thumbnailUrl && this.deps.fetchCover ? await this.deps.fetchCover(job.thumbnailUrl).catch(() => null) : null;
      // Cancelled (or paused) while the cover was being fetched: nothing is kept.
      if (state.controller.signal.aborted) throw new DOMException('Aborted', 'AbortError');
      // The wish may have arrived while it downloaded (a tap on "Save to Folder"): read the job as it is now.
      const latest = (await this.deps.store.jobs()).find((j) => j.videoId === job.videoId) ?? job;
      const item: OfflineItem = { ...pickSong(job), ...(latest.toFolder || job.toFolder ? { folderPending: true } : {}), file: result.file, mime: result.mime, bytes: result.bytes, itag: result.itag, savedAt: this.now(), cover };
      await this.deps.store.finish(item);
      wroteFile = null;
      this.jobs.delete(job.videoId);
      this.deps.onSaved?.(item);
    } catch (error) {
      if (wroteFile) await this.deps.files.remove(wroteFile).catch(() => undefined);
      // An aborted run (cancel, pause) is never a failure, whatever the step it was in then threw.
      await this.afterFailure(job, state.controller.signal.aborted ? { kind: 'abort' } : classify(error, this.online()));
    } finally {
      this.running.delete(job.videoId);
      this.deps.bus.emit();
    }
  }

  private async afterFailure(job: OfflineJob, failure: Failure) {
    // The job may have been cancelled or the queue paused while it ran: the store is the truth.
    if (failure.kind === 'abort' || !this.jobs.has(job.videoId)) return;
    if (failure.kind === 'pause') {
      await this.setPausedFromFailure(failure.reason);
      return;
    }
    let next: OfflineJob;
    if (failure.kind === 'permanent') next = { ...job, state: 'failed', reason: failure.message };
    else if (job.attempt >= MAX_RETRIES) next = { ...job, state: 'failed', reason: `${failure.message} (gave up after ${MAX_RETRIES} retries)` };
    else next = { ...job, attempt: job.attempt + 1, notBefore: this.now() + BACKOFF_MS[job.attempt]!, reason: failure.message };
    this.jobs.set(job.videoId, next);
    await this.deps.store.putJob(next);
  }

  private async setPausedFromFailure(reason: PauseReason) {
    if (this.paused) return;
    this.paused = { reason };
    await this.deps.store.setPaused(this.paused);
    for (const run of this.running.values()) run.controller.abort();
  }
}

function pickSong(song: OfflineSong): OfflineSong {
  const { videoId, title, artist, album, albumId, artistId, thumbnailUrl, durationSec, trackNumber, albumArtist } = song;
  return { videoId, title, artist, album, albumId, artistId, thumbnailUrl, durationSec, trackNumber: trackNumber ?? null, albumArtist: albumArtist ?? null };
}
