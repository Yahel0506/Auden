// @vitest-environment happy-dom
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import type { Track } from '@/data/music/types';

// The real player with its decks faked: only what the player puts on screen is read back.
vi.mock('@/auden/offline/kept', () => ({ offlineCoverUrl: (id: string) => (id === 'kept' ? 'blob:saved-cover' : null) }));
vi.mock('@/playback/deck', () => ({
  MediaDeck: class {
    mode: 'file' | 'youtube' | null = null;
    youtube = null;
    onFallback = () => {};
    version = 0;
    async load(_m: unknown, id: string) {
      this.mode = 'file';
      return { videoId: id, title: '', author: '', durationSec: 180, thumbnails: [], client: 'test', format: '', stream: { codec: null, itag: null, bitrateKbps: null, sampleRateHz: null, loudnessDb: null } };
    }
    async stop() {}
  },
}));
vi.mock('@/playback/fileEngine', () => ({ FileEngine: class { onFatal = () => {}; } }));
vi.mock('@/playback/youtube/videoDeck', () => ({ followVideo: () => {}, stopVideo: () => {} }));
vi.mock('@/extension/useExtension', () => ({ extensionQueryKey: ['extension'] }));
vi.mock('@/lib/queryClient', () => ({ queryClient: { getQueryData: () => ({ info: { capabilities: ['yt'] } }) } }));
vi.mock('@/data/music/api', () => ({ fetchRadio: async () => ({ candidates: [], seedIsVideo: false }), audioVersionOf: async () => null }));

const { usePlayer } = await import('@/state/player');

const track = (id: string): Track => ({ videoId: id, title: `T${id}`, artists: [{ name: 'Art', id: null }], album: null, durationSec: 200, explicit: false, thumbnail: `https://lh3.googleusercontent.com/${id}=w544-h544`, index: null, plays: null });
const SOURCE = { title: 'Playlist', type: 'playlist' as const };

beforeEach(() => vi.useFakeTimers());
afterEach(() => vi.useRealTimers());

describe('the cover of a song with an offline copy', () => {
  it('is the one saved with the copy, not a request to the CDN', async () => {
    usePlayer.getState().playTracks([track('kept')], 0, SOURCE);
    await vi.advanceTimersByTimeAsync(0);
    expect(usePlayer.getState().nowPlaying?.artworkUrl).toBe('blob:saved-cover');
  });

  it('stays the CDN’s for a song with no copy', async () => {
    usePlayer.getState().playTracks([track('other')], 0, SOURCE);
    await vi.advanceTimersByTimeAsync(0);
    expect(usePlayer.getState().nowPlaying?.artworkUrl).toBe('https://lh3.googleusercontent.com/other=w544-h544');
  });
});
