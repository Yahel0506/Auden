// @vitest-environment happy-dom
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import type { Track } from '@/data/music/types';

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

const { usePlayer } = await import('@/state/player');
const { startDj, stopDj, steerDj, useDj, resetDjForTests } = await import('@/auden/dj/session');
const { useDjSettings } = await import('@/auden/dj/settings');
const { useParty } = await import('@/party/listenTogether');
const { heldClipCount } = await import('@/playback/clips');
const { djTrack } = await import('@/auden/dj/model');
import type { DjDeps } from '@/auden/dj/session';
import type { Clip } from '@/auden/dj/voice';

const vid = (n: number) => String.fromCharCode(97 + (n % 26)).repeat(10) + String.fromCharCode(97 + Math.floor(n / 26));
const ARTISTS = ['Ana', 'Bo', 'Cy', 'Di', 'Ed', 'Flo', 'Gus', 'Hal', 'Ivy', 'Jo', 'Kai', 'Lia'];
const GENRES = ['rock', 'jazz', 'pop', 'folk'];
function pool(n = 72) {
  const tracks = Array.from({ length: n }, (_, i) =>
    djTrack({ id: vid(i), title: `Song ${i}`, artist: ARTISTS[i % 12]!, album: `Album ${Math.floor(i / 3)}`, genre: GENRES[i % 4]!, year: 1990 + (i % 4) * 10, plays: 5 + (i % 7), plays30: i % 3, lastPlayedAt: 1000, addedAt: i, trackNumber: Math.floor(i / 4) + 1 }),
  );
  const playable = new Map(tracks.map((t) => [t.id, { videoId: t.id, title: t.title, artists: [{ name: t.artist, id: null }], album: null, durationSec: 180, explicit: false, thumbnail: null, index: null, plays: null } as Track] as const));
  return { tracks, playable };
}
const goodClip = (): Clip => ({ blob: new Blob(['voice'], { type: 'audio/wav' }), durationMs: 1500, envelope: new Float32Array(120).fill(0.5) });
const deps = (over: Partial<DjDeps> = {}): DjDeps => ({
  loadPool: async () => pool(),
  speak: async () => ({ clip: goodClip() }),
  search: async () => [],
  now: () => 1_700_000_000_000,
  rand: () => 0.9,
  openingBudgetMs: 50,
  ...over,
});
const flush = () => new Promise((resolve) => setTimeout(resolve, 5));
const names = () => usePlayer.getState().queue.map((e) => (e.track.source === 'clip' ? 'clip' : 'song'));

beforeEach(() => {
  loads.length = 0;
  localStorage.clear();
  resetDjForTests();
  useParty.setState({ code: null } as never);
  useDjSettings.setState({ voice: true, chatty: false, language: 'en', blockSize: 5 });
  usePlayer.setState({ queue: [], index: -1, nowPlaying: null, status: 'idle', shuffle: true, repeat: 'all', autoplay: true });
});
afterEach(() => resetDjForTests());

describe('starting', () => {
  it('queues the opening clip first, then its songs, with shuffle, repeat and AutoPlay off, and shows the words', async () => {
    const r = await startDj(async () => pool(), '', deps());
    expect(r).toEqual({ ok: true });
    await flush();
    expect(names()[0]).toBe('clip');
    // The first set is clip + songs; the next is already planned behind it (one set ahead).
    expect(names().slice(1, 5).every((n) => n === 'song')).toBe(true);
    expect(names().length).toBeGreaterThanOrEqual(1 + 4);
    const { shuffle, repeat, autoplay } = usePlayer.getState();
    expect({ shuffle, repeat, autoplay }).toEqual({ shuffle: false, repeat: 'off', autoplay: false });
    expect(useDj.getState().phase).toBe('on');
    expect(useDj.getState().line).toBeTruthy();
    expect(usePlayer.getState().queue[0]!.source!.id).toBe('dj');
  });
  it('words only when the voice fails: no clip, the set still plays and the words and a notice show', async () => {
    const r = await startDj(async () => pool(), '', deps({ speak: async () => ({ failed: 'no_key' as const }) }));
    expect(r.ok).toBe(true);
    await flush();
    expect(names().every((n) => n === 'song')).toBe(true);
    expect(useDj.getState().line).toBeTruthy();
    expect(useDj.getState().notice).toMatch(/voice/i);
    expect(heldClipCount()).toBe(0);
  });
  it('a DJ with no voice (no clips, so no screen of its own) can still be stopped, and gives the settings back', async () => {
    await startDj(async () => pool(), '', deps({ speak: async () => ({ failed: 'no_key' as const }) }));
    expect(names()).not.toContain('clip');
    expect(useDj.getState().phase).toBe('on');
    stopDj();
    expect(useDj.getState().phase).toBe('idle');
    expect(usePlayer.getState().autoplay).toBe(true);
  });
  it('voice off in settings never asks the proxy', async () => {
    useDjSettings.setState({ voice: false });
    let asked = 0;
    await startDj(async () => pool(), '', deps({ speak: async () => (asked++, { failed: 'unknown' as const }) }));
    expect(asked).toBe(0);
    expect(names().every((n) => n === 'song')).toBe(true);
  });
  it('refuses in a party, and without enough music, and says why', async () => {
    useParty.setState({ code: 'ABCD' } as never);
    expect(await startDj(async () => pool(), '', deps())).toEqual({ ok: false, reason: 'party' });
    expect(usePlayer.getState().queue).toHaveLength(0);
    useParty.setState({ code: null } as never);
    expect(await startDj(async () => pool(3), '', deps({ loadPool: async () => pool(3) }))).toEqual({ ok: false, reason: 'not_enough_music' });
    expect(useDj.getState().phase).toBe('idle');
    expect(useDj.getState().notice).toMatch(/music/i);
  });
  it('a request is read against the library: the first set obeys it', async () => {
    await startDj(async () => pool(), 'something by Cy', deps());
    await flush();
    const songs = usePlayer.getState().queue.filter((e) => e.track.source !== 'clip').map((e) => e.track.artists[0]!.name);
    expect(songs.length).toBeGreaterThan(0);
    expect(songs.every((a) => a === 'Cy')).toBe(true);
  });
});

describe('the next set', () => {
  it('is planned one set ahead: starting set N asks for set N+1 (each spoken set is one voice request)', async () => {
    let spoken = 0;
    await startDj(async () => pool(), '', deps({ rand: () => 0.9, speak: async () => (spoken++, { clip: goodClip() }) }));
    await flush();
    expect(spoken).toBe(2); // the opening, and the next set already rendering behind it
    for (let i = 0; i < 12 && spoken < 3; i++) {
      usePlayer.getState().next();
      await flush();
    }
    expect(spoken).toBeGreaterThanOrEqual(3);
  });
  it('with the voice off, a set’s words appear when it starts', async () => {
    useDjSettings.setState({ voice: false });
    await startDj(async () => pool(), '', deps({ rand: () => 0.9 }));
    await flush();
    const first = useDj.getState().line;
    expect(first).toBeTruthy();
    let changed = false;
    for (let i = 0; i < 14 && !changed; i++) {
      usePlayer.getState().next();
      await flush();
      const line = useDj.getState().line;
      changed = !!line && line !== first;
    }
    expect(changed).toBe(true);
  });
});

describe('running out', () => {
  it('a library too small to keep making sets: the DJ steps aside, the queue plays out and the settings come back', async () => {
    await startDj(async () => pool(8), '', deps({ loadPool: async () => pool(8) }));
    await flush();
    for (let i = 0; i < 30 && useDj.getState().phase !== 'idle'; i++) {
      usePlayer.getState().next();
      await flush();
    }
    expect(useDj.getState().phase).toBe('idle');
    expect(useDj.getState().notice).toMatch(/everything/);
    expect(usePlayer.getState().autoplay).toBe(true);
  });
});

describe('races', () => {
  it('playing something yourself while the DJ is still rendering its opening: the DJ gives way and the album plays', async () => {
    let release!: (c: { clip: Clip }) => void;
    const slow = new Promise<{ clip: Clip }>((res) => (release = res));
    const started = startDj(async () => pool(), '', deps({ speak: () => slow, openingBudgetMs: 5000 }));
    await flush();
    usePlayer.getState().playTracks([pool().playable.get(vid(3))!, pool().playable.get(vid(4))!], 0, { title: 'album', type: 'album' });
    await flush();
    release({ clip: goodClip() });
    expect(await started).toEqual({ ok: false, reason: 'busy' });
    await flush();
    expect(names()).toEqual(['song', 'song']);
    expect(usePlayer.getState().shuffle).toBe(true); // nothing was held
    expect(useDj.getState().phase).toBe('idle');
    expect(heldClipCount()).toBe(0);
  });
  it('a second start while one is starting is turned away', async () => {
    let release!: (c: { clip: Clip }) => void;
    const slow = new Promise<{ clip: Clip }>((res) => (release = res));
    const first = startDj(async () => pool(), '', deps({ speak: () => slow, openingBudgetMs: 5000 }));
    await flush();
    expect(await startDj(async () => pool(), '', deps())).toEqual({ ok: false, reason: 'busy' });
    release({ clip: goodClip() });
    expect((await first).ok).toBe(true);
  });
  it('an append still rendering when a steer lands is thrown away: its set is not queued behind the ask', async () => {
    const gates: Array<(c: { clip: Clip }) => void> = [];
    let calls = 0;
    const speakGated: DjDeps['speak'] = () => {
      calls++;
      if (calls <= 1) return Promise.resolve({ clip: goodClip() }); // the opening is immediate
      return new Promise((res) => gates.push(res as never));
    };
    await startDj(async () => pool(), '', deps({ speak: speakGated, rand: () => 0.9 }));
    await flush(); // the next set (an append) is now waiting on its voice: gates[0]
    expect(gates).toHaveLength(1);
    const steering = steerDj('Di'); // the steer's own voice: gates[1]
    await flush();
    gates[1]!({ clip: goodClip() });
    expect((await steering).ok).toBe(true);
    const afterSteer = usePlayer.getState().queue.length;
    gates[0]!({ clip: goodClip() }); // the stale append finally finishes
    await flush();
    expect(usePlayer.getState().queue.length).toBe(afterSteer); // nothing was added behind the steer's set
  });
});

describe('words on screen', () => {
  it('the opening line and the voice notice are in the session state the DJ screen reads', async () => {
    await startDj(async () => pool(), '', deps({ speak: async () => ({ failed: 'no_key' as const }) }));
    const { line, notice } = useDj.getState();
    expect(line).toMatch(/\w+/);
    expect(notice).toMatch(/voice/i);
  });
});

describe('pending: the DJ is choosing', () => {
  it('is up from the ask until the set is cut to, with what was asked for, then down', async () => {
    let release!: (c: { clip: Clip }) => void;
    const slow = new Promise<{ clip: Clip }>((res) => (release = res));
    const starting = startDj(async () => pool(), 'something by Cy', deps({ speak: () => slow, openingBudgetMs: 5000 }));
    await flush();
    expect(useDj.getState()).toMatchObject({ phase: 'starting', pending: true, request: 'something by Cy' });
    release({ clip: goodClip() });
    await starting;
    expect(useDj.getState()).toMatchObject({ pending: false, request: null });
  });
  it('a steer raises it while the new set renders, and lowers it when the cut is made', async () => {
    const gates: Array<(c: { clip: Clip }) => void> = [];
    let calls = 0;
    const speak: DjDeps['speak'] = () => (++calls <= 1 ? Promise.resolve({ clip: goodClip() }) : new Promise((res) => gates.push(res as never)));
    await startDj(async () => pool(), '', deps({ speak, rand: () => 0.9 }));
    await flush(); // the append waits on its voice: gates[0]
    expect(useDj.getState().pending).toBe(false);
    const steering = steerDj('Di'); // the steer's voice: gates[1]
    await flush();
    expect(useDj.getState()).toMatchObject({ phase: 'on', pending: true, request: 'Di' });
    gates[1]!({ clip: goodClip() });
    expect((await steering).ok).toBe(true);
    expect(useDj.getState()).toMatchObject({ phase: 'on', pending: false, request: null });
  });
  it('a failed start leaves it down', async () => {
    await startDj(async () => pool(3), '', deps({ loadPool: async () => pool(3) }));
    expect(useDj.getState()).toMatchObject({ phase: 'idle', pending: false, request: null });
  });
  it('stopping drops it', async () => {
    await startDj(async () => pool(), '', deps());
    useDj.setState({ pending: true });
    stopDj();
    expect(useDj.getState().pending).toBe(false);
  });
});

describe('stopping and steering', () => {
  it('stop removes the clips, keeps the songs, and gives shuffle, repeat and AutoPlay back', async () => {
    await startDj(async () => pool(), '', deps());
    await flush();
    const songsBefore = names().filter((n) => n === 'song').length;
    stopDj();
    await flush();
    expect(names().includes('clip')).toBe(false);
    expect(names().filter((n) => n === 'song').length).toBe(songsBefore);
    const { shuffle, repeat, autoplay } = usePlayer.getState();
    expect({ shuffle, repeat, autoplay }).toEqual({ shuffle: true, repeat: 'all', autoplay: true });
    expect(useDj.getState().phase).toBe('idle');
  });
  it('playing something yourself ends the session without touching the queue, and restores the settings', async () => {
    await startDj(async () => pool(), '', deps());
    await flush();
    usePlayer.getState().playTracks([pool().playable.get(vid(3))!], 0, { title: 'mine', type: 'other' });
    await flush();
    expect(useDj.getState().phase).toBe('idle');
    expect(names()).toEqual(['song']);
    expect(usePlayer.getState().shuffle).toBe(true);
  });
  it('steering cuts to a new set that obeys the new ask; an unmatched clip is never left held', async () => {
    await startDj(async () => pool(), '', deps());
    await flush();
    const r = await steerDj('Di');
    expect(r).toEqual({ ok: true });
    await flush();
    const now = usePlayer.getState();
    expect(now.queue[now.index]!.track.source).toBe('clip');
    const upcoming = now.queue.slice(now.index + 1).filter((e) => e.track.source !== 'clip').map((e) => e.track.artists[0]!.name);
    expect(upcoming.every((a) => a === 'Di')).toBe(true);
  });
  it('a render that finishes after a stop queues nothing and holds no clip', async () => {
    let release!: (c: { clip: Clip }) => void;
    const slow = new Promise<{ clip: Clip }>((res) => (release = res));
    const started = startDj(async () => pool(), '', deps({ speak: () => slow, openingBudgetMs: 5000 }));
    await flush();
    stopDj();
    release({ clip: goodClip() });
    await started;
    await flush();
    expect(usePlayer.getState().queue).toHaveLength(0);
    expect(heldClipCount()).toBe(0);
  });
});
