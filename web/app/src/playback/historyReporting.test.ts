// @vitest-environment happy-dom
import { afterEach, describe, expect, it, vi } from 'vitest';
import { create } from 'zustand';

const pings: string[] = [];
const ended = new Set<() => void>();
const audio = { currentTime: 0 };
let mirrored = false;
const player = create<{ nowPlaying: { videoId: string; shareable?: boolean } | null; status: string }>()(() => ({ nowPlaying: null, status: 'idle' }));

vi.mock('@/state/player', () => ({
  usePlayer: player,
  getAudio: () => audio,
  isMirrored: () => mirrored,
  onTrackEnded: (listener: () => void) => {
    ended.add(listener);
    return () => ended.delete(listener);
  },
}));
vi.mock('@/extension/bridge', () => ({
  getBridge: () => ({
    call: async (_: string, { url }: { url: string }) => {
      pings.push(url);
      return { status: 204 };
    },
  }),
}));
vi.mock('@/extension/useExtension', () => ({ extensionQueryKey: ['extension'] }));
vi.mock('@/extension/useYtAuth', () => ({ ensureYtAuth: async () => ({ signedIn: true }) }));
vi.mock('@/lib/queryClient', () => ({
  queryClient: { getQueryData: () => ({ info: { capabilities: ['yt.stats'] } }), invalidateQueries: async () => {} },
}));
vi.mock('youtubei.js/web', () => ({ Constants: { CLIENTS: { YTMUSIC: { VERSION: '1.0' } } } }));
vi.mock('./youtube/innertube', () => ({
  getSignedInnertube: async () => ({
    session: { player: { signature_timestamp: 1 }, context: { client: { hl: 'en', gl: 'US', visitorData: 'v' } } },
    actions: {
      execute: async () => ({
        data: {
          playbackTracking: {
            videostatsPlaybackUrl: { baseUrl: 'https://s.youtube.com/api/stats/playback' },
            videostatsWatchtimeUrl: { baseUrl: 'https://s.youtube.com/api/stats/watchtime' },
          },
        },
      }),
    },
  }),
}));

const { startHistoryReporting } = await import('./historyReporting');
const playbacks = () => pings.filter((u) => u.includes('/playback')).length;

describe('history reporting', () => {
  let stop: (() => void) | null = null;
  afterEach(() => {
    stop?.();
    mirrored = false;
  });

  it('closes a play that ran to its end, so a repeat-one loop is a new play', async () => {
    stop = startHistoryReporting();
    player.setState({ nowPlaying: { videoId: 'abcdefghijk' }, status: 'playing' });
    await vi.waitFor(() => expect(playbacks()).toBe(1));

    audio.currentTime = 212;
    ended.forEach((listener) => listener());
    await vi.waitFor(() => expect(pings.some((u) => u.includes('/watchtime') && u.includes('final=1') && u.includes('et=212'))).toBe(true));

    // Repeat one: the same video starts over.
    audio.currentTime = 0;
    player.setState({ status: 'buffering' });
    player.setState({ status: 'playing' });
    await vi.waitFor(() => expect(playbacks()).toBe(2));
  });

  it('reports nothing for a local file or a DJ clip, even with an id that looks like YouTube\'s', async () => {
    stop = startHistoryReporting();
    const before = pings.length;
    player.setState({ nowPlaying: { videoId: 'cdefghijklm', shareable: false }, status: 'playing' });
    await new Promise((r) => setTimeout(r, 100));
    expect(pings.length).toBe(before);
  });

  it('reports nothing for a song that plays on another device (the Connect mirror), and closes what it had heard when the mirror starts', async () => {
    stop = startHistoryReporting();
    audio.currentTime = 0;
    const opened = playbacks();
    player.setState({ nowPlaying: { videoId: 'defghijklmn' }, status: 'playing' });
    await vi.waitFor(() => expect(playbacks()).toBe(opened + 1));

    // Another device takes the session: the same status flips, now flagged.
    mirrored = true;
    const before = pings.length;
    player.setState({ status: 'paused' });
    await vi.waitFor(() => expect(pings.slice(before).some((u) => u.includes('final=1'))).toBe(true));
    const afterClose = pings.length;

    // The mirrored song plays on (as far as the store says), and a new one starts: nothing is opened for either.
    player.setState({ status: 'playing' });
    player.setState({ nowPlaying: { videoId: 'efghijklmno' }, status: 'playing' });
    await new Promise((r) => setTimeout(r, 100));
    expect(pings.length).toBe(afterClose);
  });
});
