import { getBridge } from '@/extension/bridge';
import { enabledSources, useLyricsSettings } from '@/lyrics/settings';
import { lookupLyrics, type LookupOptions, type LyricsResult, type LyricsTrack } from '@/lyrics/repository';
import type { LyricsSourceId } from '@/lyrics/sources';
import { useJobs } from '../jobs/jobs';
import { lyricsKeyFor } from './key';
import { saveVariant } from './store';

/**
 * "Find lyrics" for a whole album or playlist: LyricsBatch.kt. Three songs at a time, each given about
 * forty seconds (a provider still hanging at 37 s is given up on and the best answer so far is used),
 * the providers and their order fixed when the run starts, a song without a known length skipped (no
 * provider can be matched without it), every other song looked up afresh (a remembered "not found"
 * must not stand in for a real answer) and the best version saved as the song's chosen one (no extras).
 */
export const PARALLELISM = 3;
export const PER_SONG_MS = 40_000;
export const SETTLE_MS = 37_000;

export interface BatchSong {
  videoId: string;
  title: string;
  artist: string;
  durationSec: number | null;
  album: string | null;
  source?: 'youtube' | 'local' | 'clip';
}

export interface BatchResult {
  found: number;
  missing: number;
  skipped: number;
}

export interface BatchDeps {
  lookup: (track: LyricsTrack, options: LookupOptions) => Promise<LyricsResult | null>;
  save: (song: BatchSong, result: LyricsResult) => Promise<void>;
  sequence: readonly LyricsSourceId[];
}

export interface BatchOptions {
  parallelism?: number;
  perSongMs?: number;
  settleMs?: number;
  signal?: AbortSignal;
  onProgress?: (done: number, total: number, result: BatchResult) => void;
}

/** The songs a batch will look up, once each, and how many were left out. */
export function batchPlan(songs: readonly BatchSong[]): { todo: BatchSong[]; skipped: number } {
  const seen = new Set<string>();
  const todo: BatchSong[] = [];
  let skipped = 0;
  for (const song of songs) {
    if (seen.has(song.videoId)) continue;
    seen.add(song.videoId);
    if (song.source === 'clip' || !song.durationSec || song.durationSec <= 0 || !song.title.trim()) skipped++;
    else todo.push(song);
  }
  return { todo, skipped };
}

export async function runBatch(songs: readonly BatchSong[], deps: BatchDeps, options: BatchOptions = {}): Promise<BatchResult> {
  const { parallelism = PARALLELISM, perSongMs = PER_SONG_MS, settleMs = SETTLE_MS, signal, onProgress } = options;
  const { todo, skipped } = batchPlan(songs);
  const result: BatchResult = { found: 0, missing: 0, skipped };
  let next = 0;
  let done = 0;

  const one = async (song: BatchSong) => {
    const track: LyricsTrack = {
      videoId: song.videoId,
      title: song.title,
      artist: song.artist,
      durationMs: Math.round((song.durationSec ?? 0) * 1000),
      album: song.album,
    };
    let timer: ReturnType<typeof setTimeout> | undefined;
    const gaveUp = new Promise<null>((resolve) => {
      timer = setTimeout(() => resolve(null), perSongMs);
    });
    try {
      const found = await Promise.race([
        deps.lookup(track, { sequence: deps.sequence, fresh: true, settleAfterMs: settleMs }).catch(() => null),
        gaveUp,
      ]);
      if (found) {
        await deps.save(song, found);
        result.found++;
      } else result.missing++;
    } catch {
      result.missing++;
    } finally {
      clearTimeout(timer);
    }
  };

  const worker = async () => {
    while (!signal?.aborted && next < todo.length) {
      await one(todo[next++]!);
      onProgress?.(++done, todo.length, { ...result });
    }
  };
  await Promise.all(Array.from({ length: Math.min(parallelism, todo.length) }, worker));
  return result;
}

// ---- Wiring to the app -----------------------------------------------------------------

const JOB_ID = 'lyrics-batch';
let running = false;

/**
 * Saves the best version as the song's chosen one. Only the best: as on the phone, a batch does not
 * fetch the "extra lyrics to keep" (those go with saving one song's lyrics), which would pile up
 * unbounded requests behind the three at a time.
 */
export async function saveBest(song: BatchSong, result: LyricsResult): Promise<void> {
  const key = await lyricsKeyFor(song.videoId);
  await saveVariant(key, result, { makeActive: true });
}

/** The extra providers' versions of a song, saved without changing which one is shown. */
export async function keepExtras(key: string, song: BatchSong | LyricsTrack, best: LyricsSourceId): Promise<void> {
  const extras = useLyricsSettings.getState().extras.filter((id) => id !== best);
  const track: LyricsTrack =
    'durationMs' in song
      ? song
      : { videoId: song.videoId, title: song.title, artist: song.artist, durationMs: Math.round((song.durationSec ?? 0) * 1000), album: song.album };
  for (const id of extras) {
    try {
      const found = await lookupLyrics(track, { only: id, fresh: true, settleAfterMs: SETTLE_MS });
      if (found) await saveVariant(key, found, { makeActive: false });
    } catch {
      // An extra that cannot be fetched is simply not kept.
    }
  }
}

/** Starts the batch for a list's songs, with its progress in the job banner. A run in progress is left alone. */
export function startLyricsBatch(songs: readonly BatchSong[], title: string): boolean {
  if (running) return false;
  running = true;
  const abort = new AbortController();
  const { todo } = batchPlan(songs);
  const jobs = useJobs.getState();
  jobs.start({
    id: JOB_ID,
    title: `Finding lyrics for ${title}`,
    total: todo.length,
    cancel: () => {
      abort.abort();
      // Up to three songs already being looked up finish first.
      useJobs.getState().update(JOB_ID, { detail: 'Stopping…', cancel: undefined });
    },
  });
  const sequence = enabledSources();
  void runBatch(songs, { lookup: lookupLyrics, save: saveBest, sequence }, {
    signal: abort.signal,
    onProgress: (done, total, r) =>
      useJobs.getState().update(JOB_ID, { done, total, ...(abort.signal.aborted ? {} : { detail: `${r.found} found` }) }),
  })
    .then((r) => {
      const parts = [`${r.found} with lyrics`, `${r.missing} without`];
      if (r.skipped) parts.push(`${r.skipped} skipped`);
      let summary = parts.join(', ');
      // Without the extension several providers cannot be reached, which reads as "without".
      if (r.missing && !getBridge()) summary += '. Some providers need the extension.';
      if (!todo.length) summary = 'Nothing to look up: these songs have no known length.';
      useJobs.getState().finish(JOB_ID, abort.signal.aborted ? `Stopped. ${summary}` : summary);
    })
    .catch(() => useJobs.getState().finish(JOB_ID, 'Something went wrong.'))
    .finally(() => {
      running = false;
    });
  return true;
}
