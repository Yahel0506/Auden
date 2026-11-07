// @vitest-environment happy-dom
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import type { Track } from '@/data/music/types';
import { clearMusicOnlyForTests, useMusicOnly } from '@/playback/musicOnly';
import { setLocalResolver } from '@/playback/source';

// The real player over fake decks, as in djPlayer.test.ts: a load records what was asked for and resolves
// at once, unless [gate] holds it.
const loads: { id: string; kind: string }[] = [];
let gate: Promise<void> | null = null;
let deckMode: 'file' | 'youtube' = 'file';

vi.mock('@/playback/deck', () => ({
  MediaDeck: class {
    mode: 'file' | 'youtube' | null = null;
    youtube: object | null = null;
    onFallback = () => {};
    private v = 0;
    get version() {
      return this.v;
    }
    async load(medium: { kind: string }, id: string) {
      this.v++;
      const held = gate;
      if (held) await held;
      this.mode = deckMode;
      this.youtube = deckMode === 'youtube' ? {} : null;
      loads.push({ id, kind: medium.kind });
      return {
        videoId: id,
        title: '',
        author: '',
        durationSec: 180,
        thumbnails: [],
        client: 'test',
        format: '',
        stream: { codec: null, itag: null, bitrateKbps: null, sampleRateHz: null, channels: null, mode: deckMode },
        loudnessDb: null,
      };
    }
    async stop() {
      this.v++;
    }
  },
}));
vi.mock('@/playback/fileEngine', () => ({
  FileEngine: class {
    onFatal = () => {};
  },
}));
const followVideo = vi.fn();
const stopVideo = vi.fn();
vi.mock('@/playback/youtube/videoDeck', () => ({ followVideo: () => followVideo(), stopVideo: () => stopVideo() }));
vi.mock('@/extension/useExtension', () => ({ extensionQueryKey: ['extension'] }));
vi.mock('@/lib/queryClient', () => ({ queryClient: { getQueryData: () => ({ info: { capabilities: ['yt'] } }) } }));
const audioVersionOf = vi.fn<(videoId: string) => Promise<Track | null>>();
vi.mock('@/data/music/api', () => ({
  fetchRadio: async () => ({ candidates: [], seedIsVideo: false }),
  audioVersionOf: (id: string) => audioVersionOf(id),
}));

const { djPlayer, getAudio, partyPlayer, setPartyHooks, usePlayer } = await import('./player');

const video = (id: string): Track => ({
  videoId: id,
  title: `${id} (Official Video)`,
  artists: [],
  album: null,
  durationSec: 200,
  explicit: false,
  thumbnail: null,
  index: null,
  plays: null,
});
const song = (id: string): Track => ({ ...video(id), title: id, album: { name: 'Album', id: null } });
const SOURCE = { title: 'Playlist', type: 'playlist' as const };
const flush = () => new Promise((resolve) => setTimeout(resolve, 0));
const ids = () => usePlayer.getState().queue.map((e) => e.track.videoId);
const entryIds = () => usePlayer.getState().queue.map((e) => e.entryId);
/** An answer the test hands over when it likes. */
function deferred<T>() {
  let resolve!: (value: T) => void;
  const promise = new Promise<T>((r) => (resolve = r));
  return { promise, resolve };
}

beforeEach(async () => {
  loads.length = 0;
  gate = null;
  deckMode = 'file';
  localStorage.clear();
  clearMusicOnlyForTests();
  audioVersionOf.mockReset();
  audioVersionOf.mockImplementation(async (id) => (id.startsWith('v') ? song(`s${id.slice(1)}`) : null));
  followVideo.mockReset();
  stopVideo.mockReset();
  usePlayer.setState({ queue: [], index: -1, nowPlaying: null, status: 'idle', shuffle: false, repeat: 'off', autoplay: false, crossfadeSec: 0, automix: false });
  useMusicOnly.setState({ preferMusicOnly: true });
  await flush();
});

afterEach(() => {
  useMusicOnly.setState({ preferMusicOnly: false });
  djPlayer.restore();
  setPartyHooks(null);
  setLocalResolver(null);
});

describe('prefer music-only version', () => {
  it('plays a music video as its song, in the same queue entry', async () => {
    usePlayer.getState().playTracks([video('v1'), song('b')], 0, SOURCE);
    const before = usePlayer.getState().queue[0]!;
    await flush();
    expect(loads.map((l) => l.id)).toEqual(['s1']);
    const after = usePlayer.getState().queue[0]!;
    expect(after.track.videoId).toBe('s1');
    expect(after.entryId).toBe(before.entryId);
    expect(after.tier).toBe(before.tier);
    expect(after.source).toBe(before.source);
    expect(usePlayer.getState().nowPlaying?.videoId).toBe('s1');
  });

  it('plays the video as itself with the setting off', async () => {
    useMusicOnly.setState({ preferMusicOnly: false });
    usePlayer.getState().playTracks([video('v1'), video('v2')], 0, SOURCE);
    await flush();
    expect(loads.map((l) => l.id)).toEqual(['v1']);
    expect(ids()).toEqual(['v1', 'v2']);
    expect(audioVersionOf).not.toHaveBeenCalled();
  });

  it('never converts in a party', async () => {
    setPartyHooks({ onIntent() {}, shouldDeferPlay: () => false, onLockedTransport: () => false, inParty: () => true });
    await partyPlayer.load([video('v1'), video('v2')], ['user', 'user'], 0, 0, true);
    await flush();
    expect(loads.map((l) => l.id)).toEqual(['v1']);
    expect(audioVersionOf).not.toHaveBeenCalled();
  });

  it('leaves the DJ’s rows alone', async () => {
    djPlayer.start([{ track: video('v1'), setIndex: 0 }, { track: video('v2'), setIndex: 0 }]);
    await flush();
    expect(loads.map((l) => l.id)).toEqual(['v1']);
    expect(ids()).toEqual(['v1', 'v2']);
    expect(audioVersionOf).not.toHaveBeenCalled();
  });

  it('plays a video with a file on the device from the file', async () => {
    setLocalResolver(async () => new Blob(['x']), () => true);
    usePlayer.getState().playTracks([video('v1')], 0, SOURCE);
    await flush();
    await flush();
    expect(loads).toEqual([{ id: 'v1', kind: 'file' }]);
    expect(audioVersionOf).not.toHaveBeenCalled();
  });

  it('converts the next row in the background, so moving on plays the song without asking again', async () => {
    usePlayer.getState().playTracks([song('a'), video('v2')], 0, SOURCE);
    const nextEntry = usePlayer.getState().queue[1]!.entryId;
    await flush();
    expect(loads.map((l) => l.id)).toEqual(['a']);
    expect(ids()).toEqual(['a', 's2']);
    expect(entryIds()[1]).toBe(nextEntry);
    usePlayer.getState().next();
    await flush();
    expect(loads.map((l) => l.id)).toEqual(['a', 's2']);
    expect(audioVersionOf).toHaveBeenCalledTimes(1);
  });

  it('converts a row that becomes next by an edit', async () => {
    usePlayer.getState().playTracks([song('a'), song('b')], 0, SOURCE);
    await flush();
    usePlayer.getState().playNext([video('v3')]);
    await flush();
    expect(ids()).toEqual(['a', 's3', 'b']);
  });

  it('waits up to 15 s for the answer, then plays the video’s own audio', async () => {
    vi.useFakeTimers();
    try {
      audioVersionOf.mockReturnValue(new Promise(() => {}));
      usePlayer.getState().playTrack(video('v1'), SOURCE);
      await vi.advanceTimersByTimeAsync(14_000);
      expect(loads).toEqual([]);
      expect(usePlayer.getState().status).toBe('loading');
      await vi.advanceTimersByTimeAsync(1_100);
      expect(loads.map((l) => l.id)).toEqual(['v1']);
      expect(ids()).toEqual(['v1']);
    } finally {
      vi.useRealTimers();
    }
  });

  it('lets the wait go at once when the setting is turned off', async () => {
    audioVersionOf.mockReturnValue(new Promise(() => {}));
    usePlayer.getState().playTrack(video('v1'), SOURCE);
    await flush();
    expect(usePlayer.getState().status).toBe('loading');
    useMusicOnly.getState().setPreferMusicOnly(false);
    await flush();
    expect(loads.map((l) => l.id)).toEqual(['v1']);
  });

  it('drops a late answer for a play the listener has moved on from', async () => {
    const answer = deferred<Track | null>();
    audioVersionOf.mockReturnValue(answer.promise);
    usePlayer.getState().playTracks([video('v1'), song('b')], 0, SOURCE);
    await flush();
    usePlayer.getState().jumpTo(1);
    await flush();
    expect(ids()).toContain('v1');
    answer.resolve(song('s1'));
    await flush();
    expect(loads.map((l) => l.id)).toEqual(['b']);
    expect(usePlayer.getState().nowPlaying?.videoId).toBe('b');
    // Nor does it rewrite the row it was for, now history.
    expect(ids()).not.toContain('s1');
  });

  it('never swaps the next row once a transition has made it the playing one', async () => {
    const answer = deferred<Track | null>();
    audioVersionOf.mockReturnValue(answer.promise);
    usePlayer.getState().playTracks([song('a'), video('v2')], 0, SOURCE);
    await flush();
    // As a crossfade or an Automix handoff does: the index moves with the video already loaded.
    usePlayer.setState({ index: 1 });
    answer.resolve(song('s2'));
    await flush();
    expect(ids()).toEqual(['a', 'v2']);
  });

  it('never swaps the next row while a crossfade is loading it', async () => {
    const answer = deferred<Track | null>();
    audioVersionOf.mockReturnValue(answer.promise);
    usePlayer.getState().playTracks([song('a'), video('v2')], 0, SOURCE);
    await flush();
    usePlayer.setState({ crossfadeSec: 3, status: 'playing' });
    const front = getAudio()!;
    Object.defineProperty(front, 'duration', { value: 100, configurable: true });
    Object.defineProperty(front, 'currentTime', { value: 98, configurable: true, writable: true });
    gate = new Promise(() => {});
    front.dispatchEvent(new Event('timeupdate'));
    await flush();
    answer.resolve(song('s2'));
    await flush();
    expect(ids()).toEqual(['a', 'v2']);
    delete (front as unknown as Record<string, unknown>).duration;
    delete (front as unknown as Record<string, unknown>).currentTime;
  });

  it('shows no picture for a music video while the setting is on', async () => {
    deckMode = 'youtube';
    useMusicOnly.setState({ preferMusicOnly: false });
    usePlayer.getState().playTrack(video('v1'), SOURCE);
    await flush();
    await flush();
    expect(followVideo).toHaveBeenCalled();
    useMusicOnly.getState().setPreferMusicOnly(true);
    await flush();
    expect(stopVideo).toHaveBeenCalled();
    // The playing track plays on as it is.
    expect(loads.map((l) => l.id)).toEqual(['v1']);
  });
});
