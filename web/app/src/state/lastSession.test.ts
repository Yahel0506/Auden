// @vitest-environment happy-dom
import { beforeEach, describe, expect, it, vi } from 'vitest';
import type { QueueEntry } from '@/playback/queue/timeline';

const restore = vi.fn(async () => {});
let player: Record<string, unknown>;
let listener: (s: Record<string, unknown>, b: Record<string, unknown>) => void = () => {};
vi.mock('./player', () => ({
  usePlayer: Object.assign(() => player, {
    getState: () => player,
    subscribe: (l: typeof listener) => {
      listener = l;
      return () => {};
    },
  }),
  getPlayhead: () => ({ sec: 42.5, paused: false }),
  sessionPlayer: { restore, canonical: () => [['e1', 0]] },
}));

const { parseSession, snapshotOf, startLastSession, KEEP_AHEAD, KEEP_BEHIND } = await import('./lastSession');

const track = (id: string, source?: string) => ({ videoId: id, title: `T${id}`, artists: [{ name: 'A', id: null }], album: null, durationSec: 100, explicit: false, thumbnail: null, index: null, plays: null, source });
const entry = (n: number, over: Partial<QueueEntry> = {}): QueueEntry => ({ entryId: `e${n}`, track: track(`v${n}`) as never, tier: 'context', source: { title: 'Album', type: 'album' }, ...over });
const state = (n: number, index: number, over: Record<string, unknown> = {}) => ({ queue: Array.from({ length: n }, (_, i) => entry(i)), index, nowPlaying: { videoId: `v${index}` }, mirror: false, status: 'playing', ...over });

beforeEach(() => {
  localStorage.clear();
  restore.mockClear();
  player = state(3, 1);
});

describe('snapshotOf', () => {
  it('keeps the queue, where in it and the position', () => {
    const s = snapshotOf(state(3, 1) as never, 12.5, null)!;
    expect(s).toMatchObject({ index: 1, positionSec: 12.5 });
    expect(s.entries.map((e) => e.entryId)).toEqual(['e0', 'e1', 'e2']);
  });

  it('trims a long queue around the playing row and keeps the index pointing at it', () => {
    const s = snapshotOf(state(1000, 500) as never, 0, null)!;
    expect(s.entries).toHaveLength(KEEP_BEHIND + 1 + KEEP_AHEAD);
    expect(s.entries[s.index]!.entryId).toBe('e500');
  });

  it('keeps nothing for a mirror, a clip, a party or an empty player', () => {
    expect(snapshotOf(state(3, 1, { mirror: true }) as never, 0, null)).toBeNull();
    expect(snapshotOf({ ...state(3, 1), queue: [entry(0), entry(1, { track: track('c', 'clip') as never })] } as never, 0, null)).toBeNull();
    expect(snapshotOf({ ...state(3, 1), queue: [entry(0), entry(1, { source: { title: 'Listen Together', type: 'queue' } })] } as never, 0, null)).toBeNull();
    expect(snapshotOf({ ...state(0, -1), nowPlaying: null } as never, 0, null)).toBeNull();
  });

  it('drops clips from the rest of the queue without moving the playing row', () => {
    const s = snapshotOf({ ...state(3, 2), queue: [entry(0), entry(1, { track: track('c', 'clip') as never }), entry(2)] } as never, 0, null)!;
    expect(s.entries.map((e) => e.entryId)).toEqual(['e0', 'e2']);
    expect(s.entries[s.index]!.entryId).toBe('e2');
  });
});

describe('parseSession', () => {
  const good = () => JSON.stringify(snapshotOf(state(3, 1) as never, 9, [['e0', 2]]));

  it('reads back what was written', () => {
    expect(parseSession(good())).toMatchObject({ index: 1, positionSec: 9, canonical: [['e0', 2]] });
  });

  it('refuses another version, damage and rows it would have to guess about', () => {
    expect(parseSession(null)).toBeNull();
    expect(parseSession('{nope')).toBeNull();
    expect(parseSession(good().replace('"v":1', '"v":2'))).toBeNull();
    expect(parseSession(good().replace('"index":1', '"index":7'))).toBeNull();
    const damaged = JSON.parse(good());
    damaged.entries[0].track = { title: 'no id' };
    expect(parseSession(JSON.stringify(damaged))).toBeNull();
  });
});

describe('startLastSession', () => {
  it('restores the saved session once, and leaves storage alone until it has landed', async () => {
    localStorage.setItem('auden:last-session', JSON.stringify(snapshotOf(state(3, 1) as never, 33, null)));
    let land: () => void = () => {};
    restore.mockImplementationOnce(() => new Promise<void>((r) => (land = r)));
    const stop = startLastSession();
    expect(restore).toHaveBeenCalledWith(expect.any(Array), 1, 33, null);
    // Half-loaded: the store says position 0, and a hide must not write it over the saved 33.
    player = state(3, 1, { status: 'loading' });
    window.dispatchEvent(new Event('pagehide'));
    expect(JSON.parse(localStorage.getItem('auden:last-session')!).positionSec).toBe(33);
    land();
    await Promise.resolve();
    await Promise.resolve();
    player = state(3, 1);
    window.dispatchEvent(new Event('pagehide'));
    expect(JSON.parse(localStorage.getItem('auden:last-session')!).positionSec).toBe(42.5);
    stop();
  });

  it('saves on a queue change and forgets an emptied queue', () => {
    vi.useFakeTimers();
    const stop = startLastSession();
    const before = state(3, 1);
    player = state(3, 2);
    listener(player, before);
    vi.advanceTimersByTime(700);
    expect(JSON.parse(localStorage.getItem('auden:last-session')!).index).toBe(2);
    const emptied = { ...state(0, -1), nowPlaying: null };
    listener(emptied, player);
    expect(localStorage.getItem('auden:last-session')).toBeNull();
    stop();
    vi.useRealTimers();
  });
});
