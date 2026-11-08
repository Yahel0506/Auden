// @vitest-environment happy-dom
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';

let player: { nowPlaying: { videoId: string; artworkUrl: string | null } | null };
let listener: (s: typeof player, b: typeof player) => void = () => {};
vi.mock('@/state/player', () => ({
  usePlayer: Object.assign(() => player, {
    getState: () => player,
    setState: (f: (s: typeof player) => Partial<typeof player> | typeof player) => {
      const before = player;
      player = { ...player, ...f(player) };
      listener(player, before);
    },
    subscribe: (l: typeof listener) => {
      listener = l;
      return () => {};
    },
  }),
}));
vi.mock('@/data/music/api', () => ({ fetchTrackLinks: vi.fn() }));

const { startCoverFallback, resetCoverFallback } = await import('./coverFallback');
const { forgetCovers, resolvedCoverOf } = await import('@/lib/resolvedCovers');

const playing = (videoId: string, artworkUrl: string | null = null) => ({ nowPlaying: { videoId, artworkUrl } });
const flush = () => vi.advanceTimersByTimeAsync(0);

beforeEach(() => {
  vi.useFakeTimers();
  forgetCovers();
  resetCoverFallback();
});
afterEach(() => vi.useRealTimers());

describe('startCoverFallback', () => {
  it('finds the real cover of a song with none and puts it on the player', async () => {
    player = playing('aaaaaaaaaaa');
    const lookup = vi.fn(async () => ({ thumbnail: 'https://yt3.googleusercontent.com/real=w544-h544' }));
    const stop = startCoverFallback(lookup);
    await flush();
    expect(lookup).toHaveBeenCalledWith('aaaaaaaaaaa');
    expect(player.nowPlaying?.artworkUrl).toBe('https://yt3.googleusercontent.com/real=w544-h544');
    expect(resolvedCoverOf('aaaaaaaaaaa')).toBe('https://yt3.googleusercontent.com/real=w544-h544');
    stop();
  });

  it('asks once per song, and puts the found cover back when the mirror rebuilds the song without it', async () => {
    player = playing('aaaaaaaaaaa');
    const lookup = vi.fn(async () => ({ thumbnail: 'https://yt3.googleusercontent.com/real' }));
    const stop = startCoverFallback(lookup);
    await flush();
    const before = player;
    player = playing('aaaaaaaaaaa');
    listener(player, before);
    await flush();
    expect(lookup).toHaveBeenCalledTimes(1);
    expect(player.nowPlaying?.artworkUrl).toBe('https://yt3.googleusercontent.com/real');
    stop();
  });

  it('does not ask for a song that has a cover, a local file or a clip', async () => {
    const lookup = vi.fn(async () => ({ thumbnail: 'x' }));
    player = playing('aaaaaaaaaaa', 'https://yt3.googleusercontent.com/own');
    const stop = startCoverFallback(lookup);
    player = playing('auden:local:abc');
    listener(player, playing('zzzzzzzzzzz'));
    player = playing('auden:clip:1');
    listener(player, playing('auden:local:abc'));
    await flush();
    expect(lookup).not.toHaveBeenCalled();
    stop();
  });

  it('shows no cover for a song YouTube Music has none for, and does not ask again for a minute', async () => {
    player = playing('aaaaaaaaaaa');
    const lookup = vi.fn(async () => null);
    const stop = startCoverFallback(lookup);
    await flush();
    expect(player.nowPlaying?.artworkUrl).toBeNull();
    const before = player;
    listener(player, before);
    await flush();
    expect(lookup).toHaveBeenCalledTimes(1);
    vi.advanceTimersByTime(61_000);
    listener(player, before);
    await flush();
    expect(lookup).toHaveBeenCalledTimes(2);
    stop();
  });
});
