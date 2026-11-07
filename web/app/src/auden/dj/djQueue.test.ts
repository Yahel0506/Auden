import { describe, expect, it } from 'vitest';
import type { Track } from '@/data/music/types';
import { asEntry, type QueueEntry, type QueueTier } from '@/playback/queue/timeline';
import { clipTrackId } from '@/playback/source';
import {
  DjLedger,
  djStartQueue,
  introUnitEnd,
  nextSpokenInARow,
  planNextDue,
  removeFromQueue,
  shouldSpeak,
  steerQueue,
  unqueuedClips,
  type DjEntry,
} from './djQueue';

const song = (id: string): Track => ({ videoId: id, title: id, artists: [], album: null, durationSec: 180, explicit: false, thumbnail: null, index: null, plays: null });
const clip = (id: string): Track => ({ ...song(clipTrackId(id)), title: `line ${id}`, source: 'clip' });

/** `c:x` is a clip, anything else a song; the entry id is the name. */
const e = (name: string, tier: QueueTier = 'context'): QueueEntry =>
  asEntry(name.startsWith('c:') ? clip(name.slice(2)) : song(name), tier, null, name);
const names = (q: readonly QueueEntry[]) => q.map((x) => x.entryId);

describe('DjLedger and planNextDue', () => {
  const dj = (setIndex: number, clipId?: string): DjEntry => ({ track: clipId ? clip(clipId) : song(`s${setIndex}`), clipId, setIndex });

  it('plans the next set when an entry of the last set starts, once', () => {
    const ledger = new DjLedger();
    ledger.record([dj(0, 'a'), dj(0), dj(0)], ['e1', 'e2', 'e3']);
    expect(planNextDue('e1', ledger)).toBe(0);
    ledger.markPlanned(0);
    expect(planNextDue('e2', ledger)).toBeNull();
    ledger.record([dj(1, 'b'), dj(1)], ['e4', 'e5']);
    expect(planNextDue('e3', ledger)).toBeNull(); // set 0 is no longer the last
    expect(planNextDue('e4', ledger)).toBe(1);
    expect(ledger.isFromLastSet('e5')).toBe(true);
    expect(ledger.isFromLastSet('e1')).toBe(false);
  });

  it('never plans from an entry that is not the DJ’s', () => {
    const ledger = new DjLedger();
    ledger.record([dj(0)], ['e1']);
    expect(planNextDue('listener', ledger)).toBeNull();
  });

  it('knows clips from songs and forgets what leaves the queue', () => {
    const ledger = new DjLedger();
    ledger.record([dj(0, 'a'), dj(0), dj(1, 'b')], ['e1', 'e2', null]);
    expect(ledger.clipEntryIds()).toEqual(['e1']);
    expect(ledger.isClip('e1')).toBe(true);
    expect(ledger.isClip('e2')).toBe(false);
    expect(ledger.last).toBe(0); // the unqueued entry is not recorded
    expect(ledger.forget(['e1', 'nope']).map((q) => q.clipId)).toEqual(['a']);
    expect(ledger.queuedClipIds()).toEqual([]);
    expect(ledger.entryIdsOfSet(0)).toEqual(['e2']);
  });
});

describe('shouldSpeak', () => {
  it('speaks for the first two sets in a row, then keeps quiet one time in three', () => {
    expect(shouldSpeak({ spokenInARow: 1, rand: () => 0 })).toBe(true);
    expect(shouldSpeak({ spokenInARow: 2, rand: () => 0 })).toBe(false);
    expect(shouldSpeak({ spokenInARow: 2, rand: () => 0.34 })).toBe(true);
    expect(shouldSpeak({ spokenInARow: 5, rand: () => 0.99 })).toBe(true);
  });

  it('always answers a set the listener cut to', () => {
    expect(shouldSpeak({ spokenInARow: 9, rand: () => 0, cut: true })).toBe(true);
  });

  it('counts spoken sets in a row', () => {
    expect(nextSpokenInARow(2, true)).toBe(3);
    expect(nextSpokenInARow(2, false)).toBe(0);
  });
});

describe('unqueuedClips', () => {
  it('lists registered clips that never reached the queue', () => {
    expect(unqueuedClips(['a', 'b', 'c', 'b'], ['b'])).toEqual(['a', 'c']);
  });
});

describe('queue edits', () => {
  it('keeps the listener’s queued songs after the opening clip and its song', () => {
    const current = [e('old'), e('u1', 'user'), e('ctx'), e('u2', 'user')];
    const queue = djStartQueue([e('c:o'), e('a'), e('b')], current, 0);
    expect(names(queue)).toEqual(['c:o', 'a', 'u1', 'u2', 'b']);
  });

  it('glues a playing clip to the song it introduces', () => {
    const queue = [e('c:o'), e('a'), e('b')];
    expect(introUnitEnd(queue, 0)).toBe(1);
    expect(introUnitEnd(queue, 1)).toBe(1);
    // A listener song already after the clip is not part of it.
    expect(introUnitEnd([e('c:o'), e('u', 'user'), e('a')], 0)).toBe(0);
  });

  it('steers: drops only the named upcoming entries, then queues after the listener’s songs', () => {
    const queue = [e('c:o'), e('a'), e('u', 'user'), e('b'), e('c:n'), e('c'), e('x')];
    const plan = steerQueue(queue, 1, new Set(['c:o', 'a', 'b', 'c:n', 'c']), [e('c:s'), e('s1'), e('s2')], false);
    expect(names(plan.queue)).toEqual(['c:o', 'a', 'u', 'c:s', 's1', 's2', 'x']);
    expect(plan.dropped).toEqual(['b', 'c:n', 'c']);
    expect(plan.play).toBeNull();
  });

  it('a cut plays the new set at once and keeps the listener’s songs after its opening', () => {
    const queue = [e('a'), e('u', 'user'), e('b')];
    const plan = steerQueue(queue, 0, new Set(['b']), [e('c:s'), e('s1'), e('s2')], true);
    expect(names(plan.queue)).toEqual(['a', 'c:s', 's1', 'u', 's2']);
    expect(plan.play).toBe(1);
  });

  it('removes clips, keeps songs, and skips a playing clip', () => {
    const queue = [e('c:o'), e('a'), e('c:n'), e('b')];
    const plan = removeFromQueue(queue, 2, new Set(['c:o', 'c:n']));
    expect(names(plan.queue)).toEqual(['a', 'b']);
    expect(plan).toMatchObject({ index: 1, skip: true });
    const notPlaying = removeFromQueue(queue, 1, new Set(['c:o', 'c:n']));
    expect(notPlaying).toMatchObject({ index: 0, skip: false });
  });

  it('leaves a playing clip that is the last thing queued', () => {
    const plan = removeFromQueue([e('a'), e('c:n')], 1, new Set(['c:n']));
    expect(names(plan.queue)).toEqual(['a', 'c:n']);
    expect(plan).toMatchObject({ index: 1, skip: false });
  });
});
