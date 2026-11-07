import type { Track } from '@/data/music/types';
import { canKeepOffline } from './actions';
import type { Followed, FollowedMap } from './types';
import { followedKey } from './types';
import type { OfflineStore } from './store';

/**
 * "Keep playlists updated" for the offline copies and Save to Folder (Android PlaylistSync, v1.12): a playlist saved
 * whole is watched, and when it gains songs the new ones are fetched too. It rides auto sync's cadence (the tab coming
 * forward, at most every six hours, behind the data-saver gate); this file is the part that decides, and it only ever
 * adds. What is new is worked out fresh each time against what was already dealt with, and nothing is recorded until it
 * has been dealt with, so a pass lost half way simply finds the same songs again.
 */

/** The most one playlist may fetch without being asked (Android ASK_ABOVE; about 1.7 GB of lossless). */
export const ASK_ABOVE = 50;
/** A cap on pages read from one YouTube playlist, whatever it says (about 5,000 songs). */
export const PAGE_CAP = 200;

export interface Plan {
  /** Songs to fetch now. */
  enqueue: string[];
  /** What to record as dealt with: everything known plus what is being fetched. */
  known: string[];
  /** More than [ASK_ABOVE] new songs and nobody said yes: they wait, unrecorded. */
  ask: string[] | null;
}

/**
 * What a playlist's current songs ([current], only the ones worth keeping) ask for beyond what was [known].
 * Songs the listener removed from offline stay in [known], so they do not come back; nothing ever leaves it.
 */
export function planSync(known: readonly string[], current: readonly string[], approved: boolean, limit = ASK_ABOVE): Plan {
  const have = new Set(known);
  const fresh = [...new Set(current)].filter((id) => !have.has(id));
  if (!fresh.length) return { enqueue: [], known: [...known], ask: null };
  if (fresh.length > limit && !approved) return { enqueue: [], known: [...known], ask: fresh };
  return { enqueue: fresh, known: [...known, ...fresh], ask: null };
}

/** What reading the playlist gave: its songs, whether the walk reached the end, or that it no longer exists. */
export type Reading = { tracks: Track[]; complete: boolean } | 'gone' | null;

export interface SyncDeps {
  store: Pick<OfflineStore, 'followed' | 'setFollowed'>;
  read(f: Followed): Promise<Reading>;
  enqueue(tracks: Track[], toFolder: boolean): Promise<void>;
  /** One record update at a time across tabs. */
  locked<T>(fn: () => Promise<T>): Promise<T>;
  now(): number;
  /** Called after the records change, so open tabs refresh. */
  changed?(): void;
}

export interface PassReport {
  /** Songs handed to the queue. */
  queued: number;
  /** Playlists waiting for a yes. */
  asking: number;
  /** Playlists that no longer exist. */
  gone: number;
  /** Playlists that could not be read this time. */
  failed: number;
}

const empty = (): PassReport => ({ queued: 0, asking: 0, gone: 0, failed: 0 });

/** Rewrites the records under the lock, from a fresh read: the listener may have unfollowed since the pass began. */
async function update(deps: SyncDeps, key: string, change: (current: Followed) => Followed | null): Promise<void> {
  await deps.locked(async () => {
    const map = await deps.store.followed();
    const current = map[key];
    if (!current) return;
    const next = change(current);
    const out: FollowedMap = { ...map };
    if (next) out[key] = next;
    else delete out[key];
    await deps.store.setFollowed(out);
  });
  deps.changed?.();
}

/** The question with the songs already dealt with taken out; null when nothing is left to ask. */
function askLeft(ask: NonNullable<Followed['ask']>, known: readonly string[]): Followed['ask'] | null {
  const ids = ask.ids.filter((id) => !known.includes(id));
  return ids.length ? { ...ask, ids } : null;
}

/** One playlist: read it, fetch what is new, and record only what was handled. */
export async function syncOne(deps: SyncDeps, key: string, f: Followed, approved: boolean, report: PassReport = empty()): Promise<PassReport> {
  const reading = await deps.read(f);
  if (reading === null) {
    report.failed++;
    return report;
  }
  if (reading === 'gone') {
    report.gone++;
    await update(deps, key, () => null);
    return report;
  }
  const keepable = reading.tracks.filter(canKeepOffline);
  const plan = planSync(f.known, keepable.map((t) => t.videoId), approved);
  if (plan.enqueue.length) {
    const wanted = new Set(plan.enqueue);
    await deps.enqueue(keepable.filter((t) => wanted.has(t.videoId)), f.toFolder);
    report.queued += plan.enqueue.length;
  }
  if (plan.ask) report.asking++;
  await update(deps, key, (current) => {
    // Added to what the record has now, not to the copy read at the start: another tab may have added to it since.
    const known = [...new Set([...current.known, ...plan.enqueue])];
    // A question about songs another pass or tab has since taken care of is gone, not asked again.
    const stillAsking = (plan.ask ?? []).filter((id) => !known.includes(id));
    return stillAsking.length ? { ...current, known, ask: { ids: stillAsking, at: deps.now() } } : { ...current, known, ask: undefined };
  });
  return report;
}

/** The playlists due, one after the other. [isDue] and [stamp] are auto sync's cadence. */
export async function syncAll(
  deps: SyncDeps,
  gate: { isDue(key: string): boolean; stamp(key: string, ok: boolean): void; cancelled?: () => boolean; pause?: () => Promise<void> },
): Promise<PassReport> {
  const report = empty();
  const map = await deps.store.followed();
  for (const [key, f] of Object.entries(map)) {
    if (gate.cancelled?.()) break;
    if (!gate.isDue(key)) continue;
    const before = report.failed;
    try {
      await syncOne(deps, key, f, false, report);
    } catch {
      // One playlist's trouble (a storage error) is its own: the others are still visited, and it is tried again sooner.
      report.failed++;
    }
    gate.stamp(key, report.failed === before);
    await gate.pause?.();
  }
  return report;
}

// ---- Following ---------------------------------------------------------------------------------------------

export interface FollowRequest {
  kind: Followed['kind'];
  id: string;
  title: string;
  /** The playlist's songs now, all of them: what is already dealt with. */
  tracks: readonly Track[];
  toFolder: boolean;
}

/** Records that a playlist was saved whole. Saving again adds to it (a folder request stays on once made). */
export async function follow(deps: Pick<SyncDeps, 'store' | 'locked' | 'changed'>, request: FollowRequest): Promise<void> {
  const key = followedKey(request.kind, request.id);
  const ids = request.tracks.filter(canKeepOffline).map((t) => t.videoId);
  await deps.locked(async () => {
    const map = await deps.store.followed();
    const current = map[key];
    const known = [...new Set([...(current?.known ?? []), ...ids])];
    await deps.store.setFollowed({
      ...map,
      [key]: {
        kind: request.kind,
        id: request.id,
        title: request.title,
        known,
        toFolder: request.toFolder || current?.toFolder === true,
        ...(current?.ask && askLeft(current.ask, known) && { ask: askLeft(current.ask, known)! }),
      },
    });
  });
  deps.changed?.();
}

export async function unfollow(deps: Pick<SyncDeps, 'store' | 'locked' | 'changed'>, key: string): Promise<void> {
  await update(deps as SyncDeps, key, () => null);
}

/** "Not now": the question goes away; the next pass asks again if the songs are still new. */
export async function dismissAsk(deps: Pick<SyncDeps, 'store' | 'locked' | 'changed'>, key: string): Promise<void> {
  await update(deps as SyncDeps, key, (current) => ({ ...current, ask: undefined }));
}
