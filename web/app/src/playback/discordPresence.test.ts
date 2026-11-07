// @vitest-environment happy-dom
import { afterEach, describe, expect, it, vi } from 'vitest';
import { create } from 'zustand';

const calls: { track: unknown }[] = [];
const clears: number[] = [];
let mirrored = false;
let audio: (EventTarget & { currentTime: number; duration: number; playbackRate: number }) | null = null;
const player = create<{ nowPlaying: { videoId: string; title: string; artist: string; shareable?: boolean; source?: string } | null; status: string; queue: []; index: number; deck: number }>()(
  () => ({ nowPlaying: null, status: 'idle', queue: [], index: 0, deck: 0 }),
);

vi.mock('@/state/player', () => ({ usePlayer: player, getAudio: () => audio, isMirrored: () => mirrored }));
vi.mock('@/extension/bridge', () => ({
  getBridge: () => ({
    call: async (_: string, payload: { track: unknown }) => {
      if (payload.track) calls.push(payload);
      else clears.push(1);
    },
  }),
}));
vi.mock('@/extension/useDiscord', () => ({ useDiscord: () => ({ state: 'checking' }), useDiscordOptions: create(() => ({ options: {} })) }));
vi.mock('@/lib/artwork', () => ({ sizedArtwork: (url: string) => url }));

const { startPublisher } = await import('./discordPresence');

describe('discord presence', () => {
  let stop: (() => void) | null = null;
  afterEach(() => {
    stop?.();
    mirrored = false;
    vi.useRealTimers();
  });

  it('republishes on a seek when the decks were made after it started', async () => {
    vi.useFakeTimers();
    stop = startPublisher(); // connected before anything played: no element yet
    await vi.advanceTimersByTimeAsync(500);

    // The first play makes the decks; the deck counter only moves on a crossfade.
    audio = Object.assign(new EventTarget(), { currentTime: 0, duration: 200, playbackRate: 1 });
    player.setState({ nowPlaying: { videoId: 'abcdefghijk', title: 't', artist: 'a' }, status: 'playing' });
    await vi.advanceTimersByTimeAsync(500);
    expect(calls).toHaveLength(1);

    audio.currentTime = 90;
    audio.dispatchEvent(new Event('seeked'));
    await vi.advanceTimersByTimeAsync(500);
    expect(calls).toHaveLength(2);
  });

  it('republishes a seek that buffers before it plays again', async () => {
    vi.useFakeTimers();
    calls.length = 0;
    audio = Object.assign(new EventTarget(), { currentTime: 0, duration: 200, playbackRate: 1 });
    player.setState({ nowPlaying: { videoId: 'bcdefghijkl', title: 't', artist: 'a' }, status: 'playing' });
    stop = startPublisher();
    await vi.advanceTimersByTimeAsync(500);
    expect(calls).toHaveLength(1);

    // Every seek of the element goes waiting → playing, and the push must survive it.
    audio.currentTime = 90;
    audio.dispatchEvent(new Event('seeked'));
    player.setState({ status: 'buffering' });
    await vi.advanceTimersByTimeAsync(500);
    player.setState({ status: 'playing' });
    await vi.advanceTimersByTimeAsync(500);
    expect(calls).toHaveLength(2);
  });

  it('puts no card on Discord for a local file, and leaves the last card up through a DJ line', async () => {
    vi.useFakeTimers();
    calls.length = 0;
    clears.length = 0;
    audio = Object.assign(new EventTarget(), { currentTime: 0, duration: 200, playbackRate: 1 });
    player.setState({ nowPlaying: { videoId: 'auden:local:1', title: 't', artist: 'a', shareable: false, source: 'local' }, status: 'playing' });
    stop = startPublisher();
    await vi.advanceTimersByTimeAsync(500);
    expect(calls).toHaveLength(0);
    expect(clears).toHaveLength(1);

    player.setState({ nowPlaying: { videoId: 'auden:clip:1', title: 'DJ', artist: 'Auden', shareable: false, source: 'clip' }, status: 'playing' });
    await vi.advanceTimersByTimeAsync(500);
    expect(calls).toHaveLength(0);
    expect(clears).toHaveLength(1);
  });

  it('announces nothing for a song that plays on another device (the Connect mirror), and takes the card down', async () => {
    vi.useFakeTimers();
    calls.length = 0;
    clears.length = 0;
    audio = Object.assign(new EventTarget(), { currentTime: 0, duration: 200, playbackRate: 1 });
    stop = startPublisher();
    player.setState({ nowPlaying: { videoId: 'abcdefghijk', title: 't', artist: 'a' }, status: 'playing' });
    await vi.advanceTimersByTimeAsync(500);
    expect(calls).toHaveLength(1);

    mirrored = true;
    player.setState({ nowPlaying: { videoId: 'bcdefghijkl', title: 'u', artist: 'b' }, status: 'playing' });
    await vi.advanceTimersByTimeAsync(500);
    audio.dispatchEvent(new Event('seeked'));
    player.setState({ status: 'paused' });
    player.setState({ status: 'playing' });
    await vi.advanceTimersByTimeAsync(500);
    expect(calls).toHaveLength(1);
    expect(clears.length).toBeGreaterThan(0);
  });
});
