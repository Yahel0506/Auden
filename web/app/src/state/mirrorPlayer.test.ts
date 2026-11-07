// @vitest-environment happy-dom
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import type { Track } from '@/data/music/types';
import { registerClip, heldClipCount } from '@/playback/clips';
import { clearMusicOnlyForTests, useMusicOnly } from '@/playback/musicOnly';
import { clipTrackId } from '@/playback/source';

// The real player over fake decks (as djPlayer.test.ts): a load records what was asked for and resolves at once.
const loads: { id: string; startSec: number | undefined; autoplay: boolean | undefined }[] = [];
const stops: number[] = [];

vi.mock('@/playback/deck', () => ({
  MediaDeck: class {
    mode: 'file' | 'youtube' | null = null;
    youtube = null;
    onFallback = () => {};
    private v = 0;
    get version() {
      return this.v;
    }
    async load(_medium: unknown, id: string, options: { startSec?: number; autoplay?: boolean } = {}) {
      this.v++;
      this.mode = 'file';
      loads.push({ id, startSec: options.startSec, autoplay: options.autoplay });
      return {
        videoId: id,
        title: '',
        author: '',
        durationSec: 180,
        thumbnails: [],
        client: 'test',
        format: '',
        stream: { codec: null, itag: null, bitrateKbps: null, sampleRateHz: null, channels: null, mode: 'file' },
        loudnessDb: null,
      };
    }
    async stop() {
      this.v++;
      stops.push(this.v);
    }
  },
}));
vi.mock('@/playback/fileEngine', () => ({ FileEngine: class { onFatal = () => {}; } }));
vi.mock('@/playback/youtube/videoDeck', () => ({ followVideo: () => {}, stopVideo: () => {} }));
// Nothing here may reach YouTube: its client is replaced by one that cannot connect.
vi.mock('@/playback/youtube/innertube', () => ({ getInnertube: async () => { throw new Error('no network in tests'); }, getSignedInnertube: async () => { throw new Error('no network in tests'); }, getMusicClient: async () => { throw new Error('no network in tests'); } }));
vi.mock('@/extension/useExtension', () => ({ extensionQueryKey: ['extension'] }));
vi.mock('@/lib/queryClient', () => ({ queryClient: { getQueryData: () => ({ info: { capabilities: ['yt'] } }) } }));
const audioVersionOf = vi.fn(async (_id: string): Promise<Track | null> => null);
const fetchRadio = vi.fn(async (_id: string) => ({ candidates: [], seedIsVideo: false }));
vi.mock('@/data/music/api', () => ({ fetchRadio: (id: string) => fetchRadio(id), audioVersionOf: (id: string) => audioVersionOf(id) }));

const { getAudio, getPlayhead, mirrorPlayer, setMirrorHooks, usePlayer, isMirrored, canonicalOrderOf, adoptShuffleOrder } = await import('./player');

const track = (id: string, over: Partial<Track> = {}): Track => ({ videoId: id, title: id, artists: [{ name: 'Art', id: null }], album: null, durationSec: 180, explicit: false, thumbnail: null, index: null, plays: null, ...over });
const view = (ids: string[], over: Partial<Parameters<typeof mirrorPlayer.show>[0]> = {}) => ({ tracks: ids.map((i) => track(i)), index: 0, playing: true, shuffle: false, repeat: 'off' as const, volume: 0.4, ...over });
const flush = () => new Promise((r) => setTimeout(r, 0));
const SOURCE = { title: 'Playlist', type: 'playlist' as const };

const hooks = {
  togglePlay: vi.fn(), next: vi.fn(), previous: vi.fn(), seek: vi.fn(), toggleShuffle: vi.fn(), cycleRepeat: vi.fn(), setVolume: vi.fn(), toggleMute: vi.fn(),
  jumpTo: vi.fn(), playNext: vi.fn(), addToQueue: vi.fn(), removeAt: vi.fn(), move: vi.fn(), clearUpNext: vi.fn(),
  leave: vi.fn(() => mirrorPlayer.leave(true)),
  playhead: vi.fn(() => ({ sec: 42, paused: false })),
};

beforeEach(async () => {
  loads.length = 0;
  stops.length = 0;
  localStorage.clear();
  clearMusicOnlyForTests();
  audioVersionOf.mockClear();
  fetchRadio.mockClear();
  for (const h of Object.values(hooks)) h.mockClear();
  mirrorPlayer.leave(true);
  usePlayer.setState({ queue: [], index: -1, nowPlaying: null, status: 'idle', shuffle: false, repeat: 'off', autoplay: false, crossfadeSec: 0, automix: false, volume: 0.8, muted: false });
  setMirrorHooks(hooks);
  await flush();
});
afterEach(() => {
  mirrorPlayer.leave(true);
  setMirrorHooks(null);
  useMusicOnly.setState({ preferMusicOnly: false });
});

describe('the mirror shows another device’s session', () => {
  it('hydrates the queue, the song, the status, shuffle, repeat and the other volume, and leaves the local volume alone', () => {
    mirrorPlayer.show({ ...view(['a', 'b', 'c'], { index: 1, shuffle: true, repeat: 'all' }), tracks: [track('a'), track('b', { title: 'Bee', thumbnail: 'https://i/b.jpg', durationSec: 201, artists: [{ name: 'X', id: null }, { name: 'Y', id: null }] }), track('c')] });
    const s = usePlayer.getState();
    expect(s.mirror).toBe(true);
    expect(isMirrored()).toBe(true);
    expect(s.queue.map((e) => e.track.videoId)).toEqual(['a', 'b', 'c']);
    expect(s.index).toBe(1);
    expect(s.nowPlaying).toMatchObject({ videoId: 'b', title: 'Bee', artist: 'X, Y', artworkUrl: 'https://i/b.jpg', durationSec: 201, medium: null, source: 'youtube', shareable: true });
    expect(s.status).toBe('playing');
    expect(s).toMatchObject({ shuffle: true, repeat: 'all', mirrorVolume: 0.4, volume: 0.8 });
    expect(JSON.parse(localStorage.getItem('bitchord:player') ?? '{}').volume ?? 0.8).toBe(0.8);
  });

  it('shows paused for a paused session and idle for none', () => {
    mirrorPlayer.show(view(['a'], { playing: false }));
    expect(usePlayer.getState().status).toBe('paused');
    mirrorPlayer.show(view([], { index: -1 }));
    expect(usePlayer.getState()).toMatchObject({ status: 'idle', nowPlaying: null });
  });

  it('keeps the rows’ keys when others come and go, and the very same queue when nothing changed', () => {
    mirrorPlayer.show(view(['a', 'b', 'c', 'b']));
    const first = usePlayer.getState().queue;
    expect(first.map((e) => e.entryId)).toEqual(['mirror:a:0', 'mirror:b:0', 'mirror:c:0', 'mirror:b:1']);
    mirrorPlayer.show(view(['a', 'b', 'c', 'b'], { playing: false }));
    expect(usePlayer.getState().queue).toBe(first);
    mirrorPlayer.show(view(['a', 'x', 'b', 'c', 'b']));
    const second = usePlayer.getState().queue;
    expect(second.map((e) => e.entryId)).toEqual(['mirror:a:0', 'mirror:x:0', 'mirror:b:0', 'mirror:c:0', 'mirror:b:1']);
    expect(second[0]).toBe(first[0]);
    expect(second[2]).toBe(first[1]);
  });

  it('reads the position from the other device through getPlayhead(), not from an element', () => {
    mirrorPlayer.show(view(['a']));
    expect(getPlayhead()).toEqual({ sec: 42, paused: false });
    hooks.playhead.mockReturnValue({ sec: 7, paused: true });
    expect(getPlayhead()).toEqual({ sec: 7, paused: true });
    mirrorPlayer.leave(true);
    expect(getPlayhead()).toBeNull();
  });

  it('makes no sound: no load, no engine call, and the playing deck is stopped when it starts', async () => {
    usePlayer.getState().playTracks([track('l1'), track('l2')], 0, SOURCE);
    await flush();
    expect(loads.map((l) => l.id)).toEqual(['l1']);
    usePlayer.setState({ status: 'paused' });
    stops.length = 0;
    mirrorPlayer.show(view(['a', 'b']));
    await flush();
    expect(stops.length).toBeGreaterThan(0);
    expect(loads.map((l) => l.id)).toEqual(['l1']);
  });

  it('keeps shuffle and repeat as this browser had them, in what is kept and after it', () => {
    usePlayer.getState().cycleRepeat(); // 'all', saved
    mirrorPlayer.show(view(['a', 'b'], { shuffle: true, repeat: 'one' }));
    usePlayer.getState().setAutoplay(true); // saves settings while mirrored
    expect(JSON.parse(localStorage.getItem('bitchord:player')!)).toMatchObject({ shuffle: false, repeat: 'all' });
    mirrorPlayer.leave();
    expect(usePlayer.getState()).toMatchObject({ mirror: false, shuffle: false, repeat: 'all', status: 'paused' });
    expect(usePlayer.getState().queue).toHaveLength(2);
  });
});

describe('every control goes to the other device', () => {
  beforeEach(() => mirrorPlayer.show(view(['a', 'b', 'c'])));

  it('routes the transport, shuffle, repeat, volume, mute and jump', () => {
    const p = usePlayer.getState();
    p.togglePlay();
    p.next();
    p.previous();
    p.seek(30);
    p.toggleShuffle();
    p.cycleRepeat();
    p.setVolume(0.9);
    p.toggleMute();
    p.jumpTo(2);
    expect(hooks.togglePlay).toHaveBeenCalledTimes(1);
    expect(hooks.next).toHaveBeenCalledTimes(1);
    expect(hooks.previous).toHaveBeenCalledTimes(1);
    expect(hooks.seek).toHaveBeenCalledWith(30);
    expect(hooks.toggleShuffle).toHaveBeenCalledTimes(1);
    expect(hooks.cycleRepeat).toHaveBeenCalledTimes(1);
    expect(hooks.setVolume).toHaveBeenCalledWith(0.9);
    expect(hooks.toggleMute).toHaveBeenCalledTimes(1);
    expect(hooks.jumpTo).toHaveBeenCalledWith(2);
    // Nothing local moved: not the volume, not the mute, not the repeat.
    expect(usePlayer.getState()).toMatchObject({ volume: 0.8, muted: false, repeat: 'off' });
    expect(loads).toHaveLength(0);
  });

  it('routes every queue edit', () => {
    const p = usePlayer.getState();
    p.playNext([track('n')]);
    p.addToQueue([track('z')]);
    p.removeAt(2);
    p.move(1, 2);
    p.clearUpNext();
    expect(hooks.playNext).toHaveBeenCalledWith([expect.objectContaining({ videoId: 'n' })]);
    expect(hooks.addToQueue).toHaveBeenCalledWith([expect.objectContaining({ videoId: 'z' })]);
    expect(hooks.removeAt).toHaveBeenCalledWith(2);
    expect(hooks.move).toHaveBeenCalledWith(1, 2);
    expect(hooks.clearUpNext).toHaveBeenCalledTimes(1);
    // The store itself is left to the other device's next state.
    expect(usePlayer.getState().queue.map((e) => e.track.videoId)).toEqual(['a', 'b', 'c']);
  });

  it('does not run AutoPlay, a music-only lookup, a crossfade, or a clip release on a mirrored queue', async () => {
    useMusicOnly.setState({ preferMusicOnly: true });
    usePlayer.setState({ autoplay: true, crossfadeSec: 6 });
    mirrorPlayer.show({ ...view(['a', 'v1']), tracks: [track('a'), track('v1', { title: 'v1 (Official Video)' })] });
    usePlayer.setState({ queue: [...usePlayer.getState().queue] });
    usePlayer.getState().setAutoplay(true);
    await flush();
    expect(fetchRadio).not.toHaveBeenCalled();
    expect(audioVersionOf).not.toHaveBeenCalled();
    expect(usePlayer.getState().queue.map((e) => e.track.videoId)).toEqual(['a', 'v1']);
  });

  it('control: the same setup, not mirrored, does ask for the radio and the music-only version', async () => {
    mirrorPlayer.leave(true);
    useMusicOnly.setState({ preferMusicOnly: true });
    usePlayer.setState({ autoplay: true });
    usePlayer.getState().playTracks([track('a'), track('v1', { title: 'v1 (Official Video)' })], 0, SOURCE);
    await flush();
    expect(fetchRadio).toHaveBeenCalled();
    expect(audioVersionOf).toHaveBeenCalled();
  });
});

describe('a clip of this browser’s own', () => {
  it('is not let go because the mirrored queue does not hold it', async () => {
    registerClip('held', new Blob(['voice']));
    usePlayer.getState().playTracks([track(clipTrackId('held'), { source: 'clip' })], 0, SOURCE);
    await flush();
    expect(heldClipCount()).toBe(1);
    usePlayer.setState({ status: 'paused' });
    mirrorPlayer.show(view(['a', 'b']));
    expect(heldClipCount()).toBe(1);
    // Control: once the listener plays something else the clip is let go as it always was.
    mirrorPlayer.leave(true);
    usePlayer.getState().playTracks([track('x')], 0, SOURCE);
    await flush();
    expect(heldClipCount()).toBe(0);
  });
});

describe('the elements of this browser say nothing about a mirrored session', () => {
  it('ignores playing, pause, timeupdate and ended from a deck that was playing before', async () => {
    usePlayer.setState({ autoplay: true });
    usePlayer.getState().playTracks([track('l1'), track('l2')], 0, SOURCE);
    await flush();
    const audio = getAudio()!;
    expect(audio).toBeTruthy();
    usePlayer.setState({ status: 'paused', autoplay: false });
    mirrorPlayer.show(view(['a', 'b']));
    fetchRadio.mockClear();
    usePlayer.setState({ autoplay: true });
    for (const name of ['playing', 'pause', 'waiting', 'timeupdate', 'durationchange', 'ended']) audio.dispatchEvent(new Event(name));
    await flush();
    const s = usePlayer.getState();
    expect(s.status).toBe('playing');
    expect(s.index).toBe(0);
    expect(s.queue.map((e) => e.track.videoId)).toEqual(['a', 'b']);
    expect(fetchRadio).not.toHaveBeenCalled();
    expect(loads.map((l) => l.id)).toEqual(['l1']);
  });
});

describe('starting something new here ends the mirror', () => {
  const ended = () => {
    expect(usePlayer.getState().mirror).toBe(false);
    expect(hooks.leave).toHaveBeenCalledTimes(1);
  };
  beforeEach(() => mirrorPlayer.show(view(['a', 'b', 'c'], { shuffle: true, repeat: 'all' })));

  it('playTracks', async () => {
    usePlayer.getState().playTracks([track('n1'), track('n2')], 0, SOURCE);
    await flush();
    ended();
    // The other device’s rows are not carried into the new context.
    expect(usePlayer.getState().queue.map((e) => e.track.videoId)).toEqual(['n1', 'n2']);
    expect(loads.map((l) => l.id)).toEqual(['n1']);
  });

  it('playTrack and playVideo', async () => {
    usePlayer.getState().playTrack(track('solo'), SOURCE);
    await flush();
    ended();
    expect(loads.map((l) => l.id)).toEqual(['solo']);
    mirrorPlayer.show(view(['a']));
    hooks.leave.mockClear();
    await usePlayer.getState().playVideo('abcdefghijk');
    await flush();
    ended();
  });

  it('a DJ start', async () => {
    const { djPlayer } = await import('./player');
    expect(djPlayer.start([{ track: track('d1'), setIndex: 0 }])).toBe(true);
    await flush();
    ended();
    djPlayer.restore();
  });

  it('a party’s queue', async () => {
    const { partyPlayer } = await import('./player');
    await partyPlayer.load([track('p1')], ['user'], 0, 0, true);
    ended();
  });

  it('a local file opened here (any playTracks of a track only this browser has)', async () => {
    usePlayer.getState().playTracks([track('auden:local:1', { source: 'local' })], 0, SOURCE);
    await flush();
    ended();
  });

  it('is not triggered by a call that starts nothing', () => {
    usePlayer.getState().playTracks([], 0, SOURCE);
    expect(usePlayer.getState().mirror).toBe(true);
    expect(hooks.leave).not.toHaveBeenCalled();
  });
});

describe('shuffle’s order', () => {
  it('lists the queue as it stood before the shuffle, and adopts another device’s', async () => {
    usePlayer.getState().playTracks(['a', 'b', 'c', 'd', 'e'].map((i) => track(i)), 0, SOURCE);
    await flush();
    usePlayer.getState().toggleShuffle();
    const { queue } = usePlayer.getState();
    expect(canonicalOrderOf(queue).map((e) => e.track.videoId)).toEqual(['a', 'b', 'c', 'd', 'e']);
    usePlayer.getState().toggleShuffle();
    expect(usePlayer.getState().queue.map((e) => e.track.videoId)).toEqual(['a', 'b', 'c', 'd', 'e']);

    // A queue that arrives shuffled, with the order it had: shuffle off lands on that list.
    await (await import('./player')).partyPlayer.load(['a', 'd', 'c', 'e', 'b'].map((i) => track(i)), ['user', 'user', 'user', 'user', 'user'], 0, 0, false);
    usePlayer.setState({ shuffle: true });
    adoptShuffleOrder(['a', 'b', 'c', 'd', 'e']);
    usePlayer.getState().toggleShuffle();
    expect(usePlayer.getState().shuffle).toBe(false);
    expect(usePlayer.getState().queue.map((e) => e.track.videoId)).toEqual(['a', 'b', 'c', 'd', 'e']);
  });
});
