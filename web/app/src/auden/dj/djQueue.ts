import type { Track } from '@/data/music/types';
import { userQueueInsertionIndex, type QueueEntry } from '@/playback/queue/timeline';
import { sourceOf } from '@/playback/source';

/**
 * The DJ's queue bookkeeping that needs no player: which queue entries are the
 * DJ's, which set each belongs to, which are its voice, when the next set is
 * due, when it keeps quiet, and the pure queue edits djPlayer (state/player.ts)
 * makes. A port of the parts of dev/auden/dj/DjSession.kt that are rules
 * rather than player calls.
 */

/** One item the DJ queues: a song of a set, or a clip of its voice (`clipId` set, the track's source 'clip'). */
export interface DjEntry {
  track: Track;
  clipId?: string;
  setIndex: number;
}

/** What the session knows about one of its entries once it is queued. */
export interface DjQueued {
  entryId: string;
  setIndex: number;
  /** The clip (playback/clips.ts) this entry plays, or null for a song. */
  clipId: string | null;
}

/** What [planNextDue] reads: a [DjLedger] satisfies it. */
export interface DjSetsView {
  get(entryId: string): DjQueued | undefined;
  /** The highest set index queued so far; -1 before the first. */
  readonly last: number;
  /** Sets whose successor has already been asked for. */
  readonly planned: ReadonlySet<number>;
}

/** The session's record of what it has queued, by queue entry id (state/player.ts djPlayer hands those out). */
export class DjLedger implements DjSetsView {
  private readonly byEntry = new Map<string, DjQueued>();
  private readonly plannedSets = new Set<number>();
  private lastSet = -1;

  get last(): number {
    return this.lastSet;
  }

  get planned(): ReadonlySet<number> {
    return this.plannedSets;
  }

  /** Records [entries] as queued under [entryIds] (same order); an entry without an id was not queued. */
  record(entries: readonly DjEntry[], entryIds: readonly (string | null | undefined)[]): void {
    entries.forEach((entry, i) => {
      const entryId = entryIds[i];
      if (!entryId) return;
      this.byEntry.set(entryId, { entryId, setIndex: entry.setIndex, clipId: entry.clipId ?? null });
      this.lastSet = Math.max(this.lastSet, entry.setIndex);
    });
  }

  /** Forgets entries taken out of the queue; returns what was known of them. */
  forget(entryIds: Iterable<string>): DjQueued[] {
    const out: DjQueued[] = [];
    for (const id of entryIds) {
      const known = this.byEntry.get(id);
      if (!known) continue;
      this.byEntry.delete(id);
      out.push(known);
    }
    return out;
  }

  get(entryId: string): DjQueued | undefined {
    return this.byEntry.get(entryId);
  }

  has(entryId: string): boolean {
    return this.byEntry.has(entryId);
  }

  isClip(entryId: string): boolean {
    return !!this.byEntry.get(entryId)?.clipId;
  }

  isFromLastSet(entryId: string): boolean {
    const known = this.byEntry.get(entryId);
    return !!known && known.setIndex === this.lastSet;
  }

  /** Every entry id the session has queued, in the order recorded. */
  entryIds(): string[] {
    return [...this.byEntry.keys()];
  }

  /** The entry ids of the DJ's voice: what a Stop takes out of the queue. */
  clipEntryIds(): string[] {
    return [...this.byEntry.values()].filter((e) => e.clipId).map((e) => e.entryId);
  }

  /** The clip ids still in the queue, for [unqueuedClips]. */
  queuedClipIds(): string[] {
    return [...this.byEntry.values()].flatMap((e) => (e.clipId ? [e.clipId] : []));
  }

  entryIdsOfSet(setIndex: number): string[] {
    return [...this.byEntry.values()].filter((e) => e.setIndex === setIndex).map((e) => e.entryId);
  }

  markPlanned(setIndex: number): void {
    this.plannedSets.add(setIndex);
  }
}

/**
 * Android's rule (DjSession.entered): when an item of the last set queued
 * starts and that set's successor hasn't been asked for, the next set is due.
 * Returns the set to plan after, or null. An entry that isn't the DJ's
 * (a song the listener queued) is never a trigger.
 */
export function planNextDue(startedEntryId: string, sets: DjSetsView): number | null {
  const known = sets.get(startedEntryId);
  if (!known || known.setIndex !== sets.last || sets.planned.has(known.setIndex)) return null;
  return known.setIndex;
}

/**
 * How many sets in a row a session counts as spoken when it starts: the
 * opening is (DjSession.spoken = 1), so the second set is always introduced.
 */
export const INITIAL_SPOKEN_IN_A_ROW = 1;

/**
 * Whether the next set opens with the DJ's voice. A DJ who talks over every
 * set is tiring: after two spoken sets in a row, one in three lets the music
 * run. A set the listener cut to (a request) is always answered. [rand] is in
 * [0, 1); injected for tests.
 */
export function shouldSpeak({ spokenInARow, rand = Math.random, cut = false }: { spokenInARow: number; rand?: () => number; cut?: boolean }): boolean {
  if (cut) return true;
  if (spokenInARow < 2) return true;
  return Math.floor(rand() * 3) !== 0;
}

/** The count after a set: one more if it opened with a clip, back to 0 if it didn't (or its line failed to render). */
export const nextSpokenInARow = (spokenInARow: number, spoke: boolean): number => (spoke ? spokenInARow + 1 : 0);

/**
 * Clips the session registered (playback/clips.ts) that never reached the
 * queue: the player's collector never sees those, so the session must
 * release them itself (a set dropped while its line rendered, a session
 * stopped before its opening was queued).
 */
export function unqueuedClips(registered: Iterable<string>, queuedClipIds: Iterable<string>): string[] {
  const queued = new Set(queuedClipIds);
  return [...new Set(registered)].filter((id) => !queued.has(id));
}

// ---- Queue edits (pure; djPlayer in state/player.ts applies them) ----------------------

/**
 * A clip is glued to what it introduces: the entries from [from] that must
 * stay together, a run of clips and the song after them. Listener songs that
 * sit after a clip already aren't part of it. 1 for anything but a clip.
 */
export function introUnitLength(queue: readonly QueueEntry[], from: number): number {
  let i = from;
  while (i + 1 < queue.length && sourceOf(queue[i]!.track) === 'clip' && queue[i + 1]!.tier !== 'user') i++;
  return i - from + 1;
}

/**
 * Where the playing entry's unit ends: a listener's "Play Next" or "Add to
 * Queue" goes after this, so it never lands between a clip and its song.
 * The playing index itself for anything but a clip.
 */
export function introUnitEnd(queue: readonly QueueEntry[], index: number): number {
  if (index < 0 || index >= queue.length) return index;
  return index + introUnitLength(queue, index) - 1;
}

/**
 * A DJ session's first queue: its entries in order, with the listener's
 * upcoming hand-queued songs kept after the opening unit (a clip and its
 * song), the way buildContextQueue keeps them after the picked track.
 */
export function djStartQueue(entries: readonly QueueEntry[], current: readonly QueueEntry[], currentIndex: number): QueueEntry[] {
  const kept = currentIndex >= 0 ? current.slice(currentIndex + 1).filter((e) => e.tier === 'user') : [];
  const unit = entries.length ? introUnitLength(entries, 0) : 0;
  return [...entries.slice(0, unit), ...kept, ...entries.slice(unit)];
}

/** The listener's songs in front of the playing one, then [fresh]; the steer's insertion point. */
function steerInsertionIndex(queue: readonly QueueEntry[], index: number): number {
  return userQueueInsertionIndex(queue, introUnitEnd(queue, index), false);
}

export interface SteerPlan {
  queue: QueueEntry[];
  /** The removed entries' ids, in queue order: only ones that were still to come. */
  dropped: string[];
  /** The index to start at once (a cut), or null to let the playing entry finish. */
  play: number | null;
}

/**
 * A steer (DjSession.steer): drops the upcoming entries named in [remove]
 * (the playing one and everything behind it stay), then puts [fresh] right
 * after the playing entry and the listener's songs queued after it. With
 * [cut] the new set plays at once; listener songs it jumps over are pinned
 * after its opening unit rather than lost (timeline.ts computeJumpOrder, rule 2).
 */
export function steerQueue(queue: readonly QueueEntry[], index: number, remove: ReadonlySet<string>, fresh: readonly QueueEntry[], cut: boolean): SteerPlan {
  const head = queue.slice(0, index + 1);
  const upcoming = queue.slice(index + 1);
  const dropped = upcoming.filter((e) => remove.has(e.entryId)).map((e) => e.entryId);
  const left = [...head, ...upcoming.filter((e) => !remove.has(e.entryId))];
  const at = steerInsertionIndex(left, index);
  if (!cut || !fresh.length || index < 0) {
    return { queue: [...left.slice(0, at), ...fresh, ...left.slice(at)], dropped, play: null };
  }
  const crossed = left.slice(index + 1, at);
  const unit = introUnitLength(fresh, 0);
  return {
    queue: [...head, ...fresh.slice(0, unit), ...crossed, ...fresh.slice(unit), ...left.slice(at)],
    dropped,
    play: index + 1,
  };
}

export interface RemovePlan {
  queue: QueueEntry[];
  /** The playing entry's index in [queue]. */
  index: number;
  /** The playing entry was removed: start [index] (the entry that followed it). */
  skip: boolean;
}

/**
 * Takes the entries named in [remove] out of the queue, played or to come.
 * The playing one goes too when something follows it, and that plays
 * instead; when it is the last, it stays (the player has nothing to go to).
 */
export function removeFromQueue(queue: readonly QueueEntry[], index: number, remove: ReadonlySet<string>): RemovePlan {
  const playing = queue[index];
  const followed = queue.slice(index + 1).some((e) => !remove.has(e.entryId));
  const skip = !!playing && remove.has(playing.entryId) && followed;
  const kept = queue.filter((e, i) => !remove.has(e.entryId) || (i === index && !skip));
  const before = queue.slice(0, Math.max(index, 0)).filter((e) => !remove.has(e.entryId)).length;
  return { queue: kept, index: index < 0 ? index : before, skip };
}
