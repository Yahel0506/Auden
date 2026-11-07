// @vitest-environment happy-dom
import { describe, expect, it } from 'vitest';
import type { Track } from '@/data/music/types';
import { ASK_ABOVE, dismissAsk, follow, planSync, syncAll, syncOne, unfollow, type Reading, type SyncDeps } from './keepUpdated';
import type { Followed, FollowedMap } from './types';

const id = (n: number) => `vid${String(n).padStart(8, '0')}`; // 11 characters, like a YouTube id
const track = (n: number): Track => ({
  videoId: id(n),
  title: `Song ${n}`,
  artists: [{ name: 'A', id: null }],
  album: null,
  durationSec: 200,
  explicit: false,
  thumbnail: null,
  index: null,
  plays: null,
});
const range = (from: number, to: number) => Array.from({ length: to - from }, (_, i) => from + i);
const tracks = (from: number, to: number) => range(from, to).map(track);
const ids = (from: number, to: number) => range(from, to).map(id);

describe('planSync', () => {
  it('fetches what is new and records it as dealt with', () => {
    expect(planSync(ids(0, 3), ids(0, 5), false)).toEqual({ enqueue: ids(3, 5), known: ids(0, 5), ask: null });
  });
  it('does not bring back a song the listener removed from offline (it is still known) or lose a song the playlist dropped', () => {
    const plan = planSync(ids(0, 5), ids(2, 4), false);
    expect(plan.enqueue).toEqual([]);
    expect(plan.known).toEqual(ids(0, 5));
  });
  it('fetches up to 50 unasked and asks beyond it, recording nothing', () => {
    expect(planSync([], ids(0, ASK_ABOVE), false).enqueue).toHaveLength(ASK_ABOVE);
    const over = planSync(ids(0, 2), ids(0, 2 + ASK_ABOVE + 1), false);
    expect(over.enqueue).toEqual([]);
    expect(over.known).toEqual(ids(0, 2));
    expect(over.ask).toHaveLength(ASK_ABOVE + 1);
  });
  it('fetches the lot once approved', () => {
    expect(planSync([], ids(0, 80), true)).toMatchObject({ ask: null, enqueue: ids(0, 80) });
  });
  it('counts a repeated song once', () => {
    expect(planSync([], [id(1), id(1), id(2)], false).enqueue).toEqual([id(1), id(2)]);
  });
});

function fake(initial: FollowedMap, reading: Reading | ((f: Followed) => Reading)) {
  let map = initial;
  const queued: Array<{ ids: string[]; toFolder: boolean }> = [];
  const deps: SyncDeps = {
    store: {
      followed: async () => map,
      setFollowed: async (next) => {
        map = next;
      },
    },
    read: async (f) => (typeof reading === 'function' ? reading(f) : reading),
    enqueue: async (t, toFolder) => void queued.push({ ids: t.map((x) => x.videoId), toFolder }),
    locked: (fn) => fn(),
    now: () => 1000,
  };
  return { deps, queued, map: () => map };
}
const rec = (over: Partial<Followed> = {}): Followed => ({ kind: 'local', id: 'p', title: 'P', known: ids(0, 3), toFolder: false, ...over });

describe('syncOne', () => {
  it('queues the new songs, with the folder wish, and records them', async () => {
    const f = fake({ 'local:p': rec({ toFolder: true }) }, { tracks: tracks(0, 5), complete: true });
    const report = await syncOne(f.deps, 'local:p', f.map()['local:p']!, false);
    expect(f.queued).toEqual([{ ids: ids(3, 5), toFolder: true }]);
    expect(report.queued).toBe(2);
    expect(f.map()['local:p']!.known).toEqual(ids(0, 5));
  });

  it('asks instead of fetching more than 50, and leaves them unrecorded so "Not now" does not lose them', async () => {
    const f = fake({ 'local:p': rec() }, { tracks: tracks(0, 60), complete: true });
    const report = await syncOne(f.deps, 'local:p', f.map()['local:p']!, false);
    expect(f.queued).toEqual([]);
    expect(report.asking).toBe(1);
    const saved = f.map()['local:p']!;
    expect(saved.known).toEqual(ids(0, 3));
    expect(saved.ask?.ids).toHaveLength(57);
    await dismissAsk(f.deps, 'local:p');
    expect(f.map()['local:p']!.ask).toBeUndefined();
    expect(f.map()['local:p']!.known).toEqual(ids(0, 3));
  });

  it('fetches them once approved, and clears the question', async () => {
    const f = fake({ 'local:p': rec({ ask: { ids: ids(3, 60), at: 1 } }) }, { tracks: tracks(0, 60), complete: true });
    await syncOne(f.deps, 'local:p', f.map()['local:p']!, true);
    expect(f.queued[0]!.ids).toHaveLength(57);
    expect(f.map()['local:p']!.ask).toBeUndefined();
    expect(f.map()['local:p']!.known).toHaveLength(60);
  });

  it('does not bring a playlist back that the listener unfollowed while the pass ran', async () => {
    const f = fake({ 'local:p': rec() }, { tracks: tracks(0, 5), complete: true });
    const original = f.map()['local:p']!;
    f.deps.read = async () => {
      await unfollow(f.deps, 'local:p');
      return { tracks: tracks(0, 5), complete: true };
    };
    await syncOne(f.deps, 'local:p', original, false);
    expect(f.map()['local:p']).toBeUndefined();
  });

  it('forgets a playlist that no longer exists, and leaves one it could not read alone', async () => {
    const gone = fake({ 'local:p': rec() }, 'gone');
    expect((await syncOne(gone.deps, 'local:p', rec(), false)).gone).toBe(1);
    expect(gone.map()).toEqual({});
    const failed = fake({ 'yt:p': rec({ kind: 'yt' }) }, null);
    expect((await syncOne(failed.deps, 'yt:p', rec({ kind: 'yt' }), false)).failed).toBe(1);
    expect(failed.map()['yt:p']).toBeDefined();
  });

  it('keeps what another tab added to the record meanwhile', async () => {
    const f = fake({ 'local:p': rec() }, { tracks: tracks(0, 5), complete: true });
    const stale = f.map()['local:p']!;
    f.deps.read = async () => {
      await f.deps.store.setFollowed({ 'local:p': { ...stale, known: [...stale.known, 'other-tab-song'] } });
      return { tracks: tracks(0, 5), complete: true };
    };
    await syncOne(f.deps, 'local:p', stale, false);
    expect(f.map()['local:p']!.known).toContain('other-tab-song');
  });

  it('nothing new: queues nothing and changes nothing', async () => {
    const f = fake({ 'local:p': rec() }, { tracks: tracks(0, 3), complete: true });
    const report = await syncOne(f.deps, 'local:p', rec(), false);
    expect(f.queued).toEqual([]);
    expect(report.queued).toBe(0);
  });

  it('ignores songs there is no audio to keep', async () => {
    const odd = { ...track(9), videoId: 'auden:local:1', source: 'local' as const };
    const f = fake({ 'local:p': rec() }, { tracks: [...tracks(0, 3), odd], complete: true });
    await syncOne(f.deps, 'local:p', rec(), false);
    expect(f.queued).toEqual([]);
  });
});

describe('bookkeeping edge cases', () => {
  it('a playlist the listener stopped updating is not visited, so nothing is fetched for it', async () => {
    const f = fake({ 'local:p': rec() }, { tracks: tracks(0, 5), complete: true });
    await unfollow(f.deps, 'local:p');
    const report = await syncAll(f.deps, { isDue: () => true, stamp: () => {} });
    expect(f.queued).toEqual([]);
    expect(report.queued).toBe(0);
  });

  it('drops a question another pass has already answered', async () => {
    const f = fake({ 'local:p': rec({ ask: { ids: ids(3, 60), at: 1 } }) }, { tracks: tracks(0, 60), complete: true });
    const stale = f.map()['local:p']!;
    f.deps.read = async () => {
      // Meanwhile the listener approved: those songs became known.
      await f.deps.store.setFollowed({ 'local:p': { ...stale, known: ids(0, 60), ask: undefined } });
      return { tracks: tracks(0, 60), complete: true };
    };
    await syncOne(f.deps, 'local:p', stale, false);
    expect(f.map()['local:p']!.ask).toBeUndefined();
  });

  it('saving again clears a question about songs that are now known', async () => {
    const f = fake({ 'local:p': rec({ ask: { ids: ids(3, 6), at: 1 } }) }, null);
    await follow(f.deps, { kind: 'local', id: 'p', title: 'P', tracks: tracks(0, 6), toFolder: false });
    expect(f.map()['local:p']!.ask).toBeUndefined();
    await follow(f.deps, { kind: 'local', id: 'p', title: 'P', tracks: tracks(0, 3), toFolder: false });
  });

  it('one playlist that throws does not stop the rest, and is stamped as a failure', async () => {
    const f = fake({ 'local:a': rec({ id: 'a' }), 'local:b': rec({ id: 'b' }) }, { tracks: tracks(0, 4), complete: true });
    f.deps.read = async (r) => {
      if (r.id === 'a') throw new Error('disk');
      return { tracks: tracks(0, 4), complete: true };
    };
    const stamped: Array<[string, boolean]> = [];
    const report = await syncAll(f.deps, { isDue: () => true, stamp: (k, ok) => void stamped.push([k, ok]) });
    expect(stamped).toEqual([['local:a', false], ['local:b', true]]);
    expect(report).toMatchObject({ failed: 1, queued: 1 });
  });
});

describe('syncAll', () => {
  it('visits only the playlists that are due and stamps each', async () => {
    const f = fake({ 'local:a': rec({ id: 'a' }), 'local:b': rec({ id: 'b' }) }, { tracks: tracks(0, 4), complete: true });
    const stamped: Array<[string, boolean]> = [];
    const report = await syncAll(f.deps, { isDue: (k) => k === 'local:b', stamp: (k, ok) => void stamped.push([k, ok]) });
    expect(stamped).toEqual([['local:b', true]]);
    expect(report.queued).toBe(1);
  });
  it('stamps a failure as one, so it is tried again sooner', async () => {
    const f = fake({ 'yt:a': rec({ kind: 'yt', id: 'a' }) }, null);
    const stamped: Array<[string, boolean]> = [];
    await syncAll(f.deps, { isDue: () => true, stamp: (k, ok) => void stamped.push([k, ok]) });
    expect(stamped).toEqual([['yt:a', false]]);
  });
  it('stops when cancelled', async () => {
    const f = fake({ 'local:a': rec({ id: 'a' }) }, { tracks: tracks(0, 4), complete: true });
    const report = await syncAll(f.deps, { isDue: () => true, stamp: () => {}, cancelled: () => true });
    expect(report.queued).toBe(0);
  });
});

describe('follow', () => {
  it('records the whole playlist as dealt with, so unloaded pages are not new later', async () => {
    const f = fake({}, null);
    await follow(f.deps, { kind: 'yt', id: 'PL1', title: 'T', tracks: tracks(0, 4), toFolder: false });
    expect(f.map()['yt:PL1']).toMatchObject({ known: ids(0, 4), toFolder: false });
  });
  it('keeps a folder request once made, and what was known', async () => {
    const f = fake({}, null);
    await follow(f.deps, { kind: 'yt', id: 'PL1', title: 'T', tracks: tracks(0, 2), toFolder: true });
    await follow(f.deps, { kind: 'yt', id: 'PL1', title: 'T', tracks: tracks(2, 4), toFolder: false });
    expect(f.map()['yt:PL1']).toMatchObject({ known: ids(0, 4), toFolder: true });
  });
});
