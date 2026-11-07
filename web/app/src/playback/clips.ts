import { clipIdOf } from './source';

/**
 * Audio made in the app (the DJ's voice), held in memory under a clip id;
 * its track's id is `auden:clip:<id>` (source.ts). A clip is let go once it
 * has been in the queue and has since left it, and isn't the one playing:
 * the player diffs every queue change (state/player.ts). One that has never
 * reached the queue is kept, so registering it before enqueueing it is safe;
 * whoever registers a clip it then doesn't enqueue should release it.
 */

interface Held {
  blob: Blob;
  /** It has been in the queue (or playing) at least once. */
  seen: boolean;
}

const clips = new Map<string, Held>();

export function registerClip(clipId: string, blob: Blob): void {
  clips.set(clipId, { blob, seen: false });
}

export function clipBlob(clipId: string): Blob | null {
  return clips.get(clipId)?.blob ?? null;
}

export function releaseClip(clipId: string): void {
  clips.delete(clipId);
}

/** How many clips are held; for tests and the debug page. */
export function heldClipCount(): number {
  return clips.size;
}

/**
 * The diff (pure): which held clips are live for the first time, and which
 * were live before and aren't now.
 */
export function clipGcPlan(held: ReadonlyMap<string, { seen: boolean }>, live: ReadonlySet<string>): { seen: string[]; release: string[] } {
  const seen: string[] = [];
  const release: string[] = [];
  for (const [id, clip] of held) {
    if (live.has(id)) {
      if (!clip.seen) seen.push(id);
    } else if (clip.seen) release.push(id);
  }
  return { seen, release };
}

/**
 * Applies the diff to the registry, given every track id still live (the
 * queue's and the one playing). Returns the clips released. An object URL
 * already made from a released blob keeps playing: the element holds the blob.
 */
export function collectClips(liveTrackIds: Iterable<string>): string[] {
  if (!clips.size) return [];
  const live = new Set<string>();
  for (const trackId of liveTrackIds) {
    const id = clipIdOf(trackId);
    if (id) live.add(id);
  }
  const { seen, release } = clipGcPlan(clips, live);
  for (const id of seen) clips.get(id)!.seen = true;
  for (const id of release) clips.delete(id);
  return release;
}

// For browser-driven checks: a fake DJ voice to play (next to __bcPlayer).
if (import.meta.env.DEV && typeof window !== 'undefined') Object.assign(window, { __bcClips: { registerClip } });
