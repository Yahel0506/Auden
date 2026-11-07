import { describe, expect, it } from 'vitest';
import { reconcile, type ReconcileEntry } from './reconcile';

/** Existing entries whose song keys are `k<trackId>`; the holder is the first entry with that track. */
function run(existing: ReconcileEntry[], opts: { sourceIds: string[] | null; complete: boolean; matched: Array<{ spotifyId?: string; trackKey: string }> }) {
  return reconcile({
    existing,
    ...opts,
    holderOf: (key) => existing.find((e) => `k${e.trackId}` === key)?.entryId,
  });
}

describe('reconcile', () => {
  const synced = [
    { entryId: 1, trackId: 10, fromSpotifyId: 'S1' },
    { entryId: 2, trackId: 20, fromSpotifyId: 'S2' },
  ];

  it('a short read (not complete) removes nothing', () => {
    const r = run(synced, { sourceIds: ['S1'], complete: false, matched: [{ spotifyId: 'S1', trackKey: 'k10' }] });
    expect(r.remove).toEqual([]);
    expect(r.add).toEqual([]);
  });

  it('removes a synced entry the complete original no longer lists', () => {
    const r = run(synced, { sourceIds: ['S1'], complete: true, matched: [{ spotifyId: 'S1', trackKey: 'k10' }] });
    expect(r.remove).toEqual([2]);
  });

  it('keeps the entry of a listed id that did not match this time', () => {
    const r = run(synced, { sourceIds: ['S1', 'S2'], complete: true, matched: [{ spotifyId: 'S1', trackKey: 'k10' }] });
    expect(r.remove).toEqual([]);
  });

  it('never removes a hand-added entry, even when a Spotify id matched its song and then left', () => {
    const hand = [{ entryId: 5, trackId: 50 }];
    // S matches the hand-added song: nothing is added, and the entry stays hand-added.
    const first = run(hand, { sourceIds: ['S'], complete: true, matched: [{ spotifyId: 'S', trackKey: 'k50' }] });
    expect(first).toEqual({ remove: [], add: [], retag: [] });
    // S leaves the original.
    const second = run(hand, { sourceIds: ['T'], complete: true, matched: [{ spotifyId: 'T', trackKey: 'k60' }] });
    expect(second.remove).toEqual([]);
    expect(second.add.map((m) => m.spotifyId)).toEqual(['T']);
  });

  it('two Spotify ids matching one video add one entry; when one leaves the entry stays under the other', () => {
    const added = run([], { sourceIds: ['S1', 'S2'], complete: true, matched: [{ spotifyId: 'S1', trackKey: 'kv' }, { spotifyId: 'S2', trackKey: 'kv' }] });
    expect(added.add).toEqual([{ spotifyId: 'S1', trackKey: 'kv' }]);
    const existing = [{ entryId: 9, trackId: 7, fromSpotifyId: 'S1' }];
    const r = run(existing, { sourceIds: ['S2'], complete: true, matched: [{ spotifyId: 'S2', trackKey: 'k7' }] });
    expect(r.remove).toEqual([]);
    expect(r.retag).toEqual([{ entryId: 9, fromSpotifyId: 'S2' }]);
    expect(r.add).toEqual([]);
  });

  it('all matches failed: nothing changes, even with a complete read', () => {
    expect(run(synced, { sourceIds: ['S9'], complete: true, matched: [] })).toEqual({ remove: [], add: [], retag: [] });
  });

  it('a YouTube copy only gains songs', () => {
    const r = run(synced, { sourceIds: null, complete: true, matched: [{ trackKey: 'k10' }, { trackKey: 'k30' }, { trackKey: 'k30' }] });
    expect(r.remove).toEqual([]);
    expect(r.add).toEqual([{ trackKey: 'k30' }]);
  });

  it('a Spotify id already in the copy is not added again when it matches another video now', () => {
    const r = run(synced, { sourceIds: ['S1', 'S2'], complete: true, matched: [{ spotifyId: 'S1', trackKey: 'k99' }] });
    expect(r.add).toEqual([]);
  });

  it('an id that matched counts as listed even if the caller left it out', () => {
    const r = run(synced, { sourceIds: [], complete: true, matched: [{ spotifyId: 'S2', trackKey: 'k20' }] });
    expect(r.remove).toEqual([1]);
  });
});
