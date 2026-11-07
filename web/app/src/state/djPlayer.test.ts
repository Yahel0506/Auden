// @vitest-environment happy-dom
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import type { DjEntry } from '@/auden/dj/djQueue';
import type { Track } from '@/data/music/types';
import { registerClip } from '@/playback/clips';
import { clipTrackId } from '@/playback/source';

// The real player over fake decks: a load resolves at once and records what was asked for.
const loads: { id: string; kind: string; autoplay: boolean | undefined }[] = [];

vi.mock('@/playback/deck', () => ({
  MediaDeck: class {
    mode: 'file' | 'youtube' | null = null;
    youtube = null;
    onFallback = () => {};
    private v = 0;
    get version() {
      return this.v;
    }
    async load(medium: { kind: string }, id: string, options: { autoplay?: boolean } = {}) {
      this.v++;
      // 'file' keeps the picture player (and Shaka) out of the test.
      this.mode = 'file';
      loads.push({ id, kind: medium.kind, autoplay: options.autoplay });
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
    }
  },
}));
vi.mock('@/playback/fileEngine', () => ({
  FileEngine: class {
    onFatal = () => {};
  },
}));
vi.mock('@/extension/useExtension', () => ({ extensionQueryKey: ['extension'] }));
vi.mock('@/lib/queryClient', () => ({ queryClient: { getQueryData: () => ({ info: { capabilities: ['yt'] } }) } }));
vi.mock('@/data/music/api', () => ({ fetchRadio: async () => ({ candidates: [], seedIsVideo: false }) }));

const { djPlayer, setPartyHooks, usePlayer } = await import('./player');

const song = (id: string): Track => ({ videoId: id, title: id, artists: [], album: null, durationSec: 180, explicit: false, thumbnail: null, index: null, plays: null });
let clipSeq = 0;
/** A registered clip, as the session makes them. */
function line(setIndex: number): DjEntry {
  const clipId = `clip${++clipSeq}`;
  registerClip(clipId, new Blob(['voice'], { type: 'audio/mpeg' }));
  return { track: { ...song(clipTrackId(clipId)), title: 'Auden DJ', source: 'clip' }, clipId, setIndex };
}
const songOf = (id: string, setIndex: number): DjEntry => ({ track: song(id), setIndex });

/** The queue as names: a clip is `clip`, a song its id. */
const order = () => usePlayer.getState().queue.map((e) => (e.track.source === 'clip' ? 'clip' : e.track.videoId));
const playing = () => {
  const { queue, index } = usePlayer.getState();
  return queue[index]?.track.source === 'clip' ? 'clip' : queue[index]?.track.videoId;
};
const flush = () => new Promise((resolve) => setTimeout(resolve, 0));
const settings = () => {
  const { shuffle, repeat, autoplay } = usePlayer.getState();
  return { shuffle, repeat, autoplay };
};

beforeEach(() => {
  loads.length = 0;
  localStorage.clear();
  usePlayer.setState({ queue: [], index: -1, nowPlaying: null, status: 'idle', shuffle: false, repeat: 'off', autoplay: false });
});

afterEach(() => {
  djPlayer.restore();
  setPartyHooks(null);
});

describe('djPlayer', () => {
  it('starts on its opening clip with shuffle, repeat and AutoPlay off, even with shuffle on before', async () => {
    usePlayer.setState({ shuffle: true, repeat: 'all', autoplay: true });
    usePlayer.getState().playTracks([song('p1'), song('p2'), song('p3')], 0, { title: 'Playlist', type: 'playlist' });
    usePlayer.getState().addToQueue([song('mine')]);
    await flush();

    expect(djPlayer.start([line(0), songOf('a', 0), songOf('b', 0), songOf('c', 0)])).toBe(true);
    await flush();
    expect(order()).toEqual(['clip', 'a', 'mine', 'b', 'c']);
    expect(playing()).toBe('clip');
    expect(loads.at(-1)?.kind).toBe('file');
    expect(settings()).toEqual({ shuffle: false, repeat: 'off', autoplay: false });
    // What is saved meanwhile is the listener's own: a closed tab doesn't lose it.
    expect(JSON.parse(localStorage.getItem('bitchord:player')!)).toMatchObject({ shuffle: true, repeat: 'all', autoplay: true });
  });

  it('keeps its sets in order around songs the listener queues', async () => {
    djPlayer.start([line(0), songOf('a', 0), songOf('b', 0)]);
    await flush();
    // The opening clip is playing: the listener's songs go after the song it introduces.
    usePlayer.getState().addToQueue([song('x')]);
    usePlayer.getState().playNext([song('y')]);
    expect(order()).toEqual(['clip', 'a', 'y', 'x', 'b']);

    djPlayer.append([line(1), songOf('c', 1), songOf('d', 1)]);
    expect(order()).toEqual(['clip', 'a', 'y', 'x', 'b', 'clip', 'c', 'd']);

    // On the last song of a set, a queued song goes before the next set's clip, never between it and its song.
    usePlayer.getState().jumpTo(4);
    await flush();
    expect(playing()).toBe('b');
    usePlayer.getState().addToQueue([song('z')]);
    expect(order().slice(-4)).toEqual(['z', 'clip', 'c', 'd']);
    // Turning AutoPlay back on mid-session: a new set still goes ahead of its rows.
    usePlayer.setState((s) => ({ queue: [...s.queue, { entryId: 'radio', track: song('r'), tier: 'autoplay', source: null }] }));
    djPlayer.append([songOf('e', 2)]);
    expect(order().slice(-2)).toEqual(['e', 'r']);
  });

  it('tells the session which entry started', async () => {
    const started: string[] = [];
    const off = djPlayer.onEntryStart((id) => started.push(id));
    const opening = [line(0), songOf('a', 0)];
    djPlayer.start(opening);
    await flush();
    const ids = djPlayer.entryIdsOf(opening);
    expect(started).toEqual([ids[0]]);
    usePlayer.getState().next();
    await flush();
    expect(started).toEqual(ids);
    off();
  });

  it('steers: drops only its upcoming entries and queues the new set after the listener’s songs', async () => {
    const first = [line(0), songOf('a', 0), songOf('b', 0)];
    djPlayer.start(first);
    await flush();
    const second = djPlayer.append([line(1), songOf('c', 1)]);
    usePlayer.getState().next(); // 'a'
    await flush();
    usePlayer.getState().addToQueue([song('mine')]);
    const all = [...djPlayer.entryIdsOf(first).map((id) => id!), ...second];

    const { entryIds, dropped } = djPlayer.replaceUpcoming(all, [line(2), songOf('s', 2)]);
    expect(order()).toEqual(['clip', 'a', 'mine', 'clip', 's']);
    expect(dropped).toEqual([all[2], ...second]);
    expect(playing()).toBe('a');
    expect(djPlayer.upcomingEntryIds().slice(1)).toEqual(entryIds);

    // A cut plays the new set at once; the listener's song waits after its opening.
    djPlayer.replaceUpcoming(entryIds, [line(3), songOf('t', 3), songOf('u', 3)], { cut: true });
    await flush();
    expect(order()).toEqual(['clip', 'a', 'clip', 't', 'mine', 'u']);
    expect(playing()).toBe('clip');
  });

  it('a Stop takes its clips out, keeps the songs and skips a clip that is playing', async () => {
    usePlayer.setState({ shuffle: true, repeat: 'one', autoplay: false });
    const first = [line(0), songOf('a', 0)];
    djPlayer.start(first);
    await flush();
    const second = [line(1), songOf('b', 1)];
    djPlayer.append(second);
    usePlayer.getState().jumpTo(2); // the second set's clip
    await flush();
    expect(playing()).toBe('clip');

    const clipIds = [...djPlayer.entryIdsOf([first[0]!, second[0]!])].map((id) => id!);
    djPlayer.remove(clipIds);
    djPlayer.restore();
    await flush();
    expect(order()).toEqual(['a', 'b']);
    expect(playing()).toBe('b');
    expect(loads.at(-1)?.id).toBe('b');
    expect(settings()).toEqual({ shuffle: true, repeat: 'one', autoplay: false });
    expect(djPlayer.isActive()).toBe(false);
  });

  it('a context the listener plays ends the session first and brings the settings back', async () => {
    usePlayer.setState({ shuffle: true, repeat: 'off', autoplay: true });
    djPlayer.start([line(0), songOf('a', 0)]);
    await flush();
    let seen: string[] | null = null;
    const off = djPlayer.onBeforeNewContext(() => {
      seen = order();
    });
    usePlayer.getState().playTracks([song('p1'), song('p2'), song('p3')], 1, { title: 'Album', type: 'album' });
    off();
    // Told before the new queue was built, and the new queue is the listener's (shuffled: p2 leads).
    expect(seen).toEqual(['clip', 'a']);
    expect(settings()).toEqual({ shuffle: true, repeat: 'off', autoplay: true });
    expect(djPlayer.isActive()).toBe(false);
    expect(order()[0]).toBe('p2');
    expect(order()).toHaveLength(3);
  });

  it('listener settings changed during the session are not overridden', async () => {
    usePlayer.setState({ shuffle: false, repeat: 'all', autoplay: true });
    djPlayer.start([line(0), songOf('a', 0)]);
    await flush();
    usePlayer.getState().cycleRepeat(); // off → all, by the listener
    usePlayer.getState().cycleRepeat(); // all → one
    djPlayer.restore();
    expect(settings()).toEqual({ shuffle: false, repeat: 'one', autoplay: true });
  });

  it('refuses to start in a party', () => {
    setPartyHooks({ onIntent: () => {}, shouldDeferPlay: () => false, onLockedTransport: () => false, inParty: () => true });
    usePlayer.setState({ shuffle: true });
    expect(djPlayer.start([line(0), songOf('a', 0)])).toBe(false);
    expect(order()).toEqual([]);
    expect(settings().shuffle).toBe(true);
    expect(djPlayer.isActive()).toBe(false);
  });
});
