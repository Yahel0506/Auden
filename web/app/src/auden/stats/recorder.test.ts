// @vitest-environment happy-dom
import 'fake-indexeddb/auto';
import { IDBFactory } from 'fake-indexeddb';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';

// The player is replaced by a stand-in whose state the test moves by hand.
const player = vi.hoisted(() => ({
  state: { status: 'idle', nowPlaying: null as unknown, queue: [] as unknown[], index: 0 },
  paused: true,
  mirrored: false,
  duration: 200,
  ended: new Set<() => void>(),
}));
vi.mock('@/state/player', () => ({
  usePlayer: { getState: () => player.state },
  getAudio: () => ({ duration: player.duration }),
  getPlayhead: () => ({ sec: 0, paused: player.paused }),
  isMirrored: () => player.mirrored,
  onTrackEnded: (l: () => void) => {
    player.ended.add(l);
    return () => player.ended.delete(l);
  },
}));

import { closeAudenDb } from '../db/schema';
import { listeningMonths } from './listening';
import { startPlayRecorder } from './recorder';

const nowPlaying = (videoId: string) => ({ videoId, title: 'T', artist: 'A', artworkUrl: null, durationSec: 40, streamInfo: null, stream: null });
const play = (videoId = 'v1') => {
  player.state.status = 'playing';
  player.state.nowPlaying = nowPlaying(videoId);
  player.paused = false;
};

let stop: () => void;
beforeEach(() => {
  globalThis.indexedDB = new IDBFactory();
  vi.useFakeTimers({ toFake: ['setInterval', 'clearInterval', 'performance', 'Date'] });
  vi.setSystemTime(new Date(2026, 10, 5, 12, 0, 0));
  player.state = { status: 'idle', nowPlaying: null, queue: [], index: 0 };
  player.paused = true;
  player.mirrored = false;
  player.duration = 40; // a 40 s track: a play is 30 s
  player.ended.clear();
  stop = startPlayRecorder();
});
afterEach(async () => {
  stop();
  vi.useRealTimers();
  // stop() flushes: let that write land before the next test swaps the database.
  await new Promise((r) => setTimeout(r, 50));
  await closeAudenDb();
});

const settle = async () => {
  vi.useRealTimers();
  await new Promise((r) => setTimeout(r, 50));
  vi.useFakeTimers({ toFake: ['setInterval', 'clearInterval', 'performance', 'Date'] });
};

describe('startPlayRecorder', () => {
  it('records nothing while idle, paused or while the track in front is not moving', async () => {
    vi.advanceTimersByTime(60_000);
    play();
    player.paused = true;
    vi.advanceTimersByTime(60_000);
    await settle();
    expect(await listeningMonths()).toEqual([]);
  });

  it('writes the listening of a played track when it is paused, counting one play', async () => {
    play();
    vi.advanceTimersByTime(5_000 * 8); // anchor + 7 steps = 35 s
    player.state.status = 'paused';
    player.paused = true;
    vi.advanceTimersByTime(5_000);
    await settle();
    const [bucket] = await listeningMonths();
    expect(bucket!.tracks).toHaveLength(1);
    expect(bucket!.tracks[0]).toMatchObject({ id: 'v1', ms: 35_000, plays: 1 });
  });

  it('flushes about every thirty seconds while playing', async () => {
    play();
    vi.advanceTimersByTime(5_000 * 7); // sixth sample is the flush
    await settle();
    expect((await listeningMonths())[0]!.tracks[0]!.ms).toBeGreaterThan(0);
  });

  it('flushes when the tab is hidden', async () => {
    play();
    vi.advanceTimersByTime(5_000 * 3);
    Object.defineProperty(document, 'visibilityState', { value: 'hidden', configurable: true });
    document.dispatchEvent(new Event('visibilitychange'));
    await settle();
    expect((await listeningMonths())[0]!.tracks[0]!.ms).toBe(10_000);
    Object.defineProperty(document, 'visibilityState', { value: 'visible', configurable: true });
  });

  it('counts a repeated track twice when it ends in between', async () => {
    play();
    vi.advanceTimersByTime(5_000 * 7);
    player.ended.forEach((l) => l());
    vi.advanceTimersByTime(5_000 * 8);
    player.state.status = 'paused';
    player.paused = true;
    vi.advanceTimersByTime(5_000);
    await settle();
    expect((await listeningMonths())[0]!.tracks[0]!.plays).toBe(2);
  });

  it('does not count a DJ line as listening: it stops the clock like a stall', async () => {
    play('auden:clip:1');
    (player.state.nowPlaying as { source?: string }).source = 'clip';
    vi.advanceTimersByTime(5_000 * 8);
    player.state.status = 'paused';
    player.paused = true;
    vi.advanceTimersByTime(5_000);
    await settle();
    expect(await listeningMonths()).toEqual([]);
  });

  it('records nothing for a song that plays on another device (the Connect mirror)', async () => {
    play();
    player.mirrored = true;
    vi.advanceTimersByTime(5_000 * 12);
    player.state.status = 'paused';
    player.paused = true;
    vi.advanceTimersByTime(5_000);
    await settle();
    expect(await listeningMonths()).toEqual([]);
  });
});
