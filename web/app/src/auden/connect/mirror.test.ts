// @vitest-environment happy-dom
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import type { ClientMessage, Command, ConnectTrack, PlaybackState, ServerMessage } from '@bitchord/shared/connect';
import type { Track } from '@/data/music/types';

// The Connect session over the REAL player (decks faked): a second device is a hand-fed ServerMessage, and what this
// browser sends is read back from [sent].
const sent: ClientMessage[] = [];
let handler: (m: ServerMessage) => void = () => {};
let setStatus: (s: 'off' | 'connecting' | 'online') => void = () => {};
let clientOk = true;
const loads: { id: string; startSec: number | undefined; autoplay: boolean | undefined }[] = [];

vi.mock('@/config', () => ({ config: { connectUrl: 'https://connect.test' } }));
vi.mock('@/party/listenTogether', () => ({ accountUserId: async () => 'a'.repeat(32), deviceId: () => 'web1', useParty: { getState: () => ({ code: null }) } }));
vi.mock('./client', () => ({
  ConnectClient: class {
    constructor(o: { onMessage: typeof handler; onStatus: typeof setStatus }) {
      handler = o.onMessage;
      setStatus = o.onStatus;
    }
    start() {}
    stop() {}
    send(m: ClientMessage) {
      if (clientOk) sent.push(m);
      return clientOk;
    }
  },
}));
vi.mock('@/playback/deck', () => ({
  MediaDeck: class {
    mode: 'file' | 'youtube' | null = null;
    youtube = null;
    onFallback = () => {};
    private v = 0;
    get version() {
      return this.v;
    }
    async load(_m: unknown, id: string, options: { startSec?: number; autoplay?: boolean } = {}) {
      this.v++;
      this.mode = 'file';
      loads.push({ id, startSec: options.startSec, autoplay: options.autoplay });
      return { videoId: id, title: '', author: '', durationSec: 180, thumbnails: [], client: 'test', format: '', stream: { codec: null, itag: null, bitrateKbps: null, sampleRateHz: null, channels: null, mode: 'file' }, loudnessDb: null };
    }
    async stop() {
      this.v++;
    }
  },
}));
vi.mock('@/playback/fileEngine', () => ({ FileEngine: class { onFatal = () => {}; } }));
vi.mock('@/playback/youtube/videoDeck', () => ({ followVideo: () => {}, stopVideo: () => {} }));
vi.mock('@/extension/useExtension', () => ({ extensionQueryKey: ['extension'] }));
vi.mock('@/lib/queryClient', () => ({ queryClient: { getQueryData: () => ({ info: { capabilities: ['yt'] } }) } }));
vi.mock('@/data/music/api', () => ({ fetchRadio: async () => ({ candidates: [], seedIsVideo: false }), audioVersionOf: async () => null }));

const { usePlayer, getPlayhead, mirrorPlayer } = await import('@/state/player');
const { startConnect, switchTo, remoteCommand, localState } = await import('./session');
const { useConnect } = await import('./store');

const NOW = 1_000_000;
const ct = (id: string, extra: Partial<ConnectTrack> = {}): ConnectTrack => ({ videoId: id, title: `T${id}`, artist: 'Art', durationSec: 200, ...extra });
const phoneState = (over: Partial<PlaybackState> = {}): PlaybackState => ({
  queue: ['a', 'b', 'c', 'd'].map((i) => ct(i)), index: 1, positionMs: 30_000, isPlaying: true, volume: 0.4, shuffle: false, repeat: 'off', ...over,
});
const devices = [
  { deviceId: 'web1', name: 'Chrome on Linux', platform: 'web' as const, online: true },
  { deviceId: 'phone', name: 'Pixel', platform: 'android' as const, online: true },
];
const track = (id: string, over: Partial<Track> = {}): Track => ({ videoId: id, title: `T${id}`, artists: [{ name: 'Art', id: null }], album: null, durationSec: 200, explicit: false, thumbnail: null, index: null, plays: null, ...over });
const SOURCE = { title: 'Playlist', type: 'playlist' as const };
const flush = () => vi.advanceTimersByTimeAsync(0);
const ids = () => usePlayer.getState().queue.map((e) => e.track.videoId);
const commands = () => sent.filter((m): m is Extract<ClientMessage, { type: 'command' }> => m.type === 'command').map((m) => m.command);
const last = (): Command => commands().at(-1)!;

let stop: (() => void) | null = null;
async function join(over: Partial<Extract<ServerMessage, { type: 'welcome' }>> = {}) {
  sent.length = 0;
  stop = await startConnect();
  setStatus('online');
  handler({ type: 'welcome', serverMs: NOW, stateServerMs: NOW - 1_000, devices, activeDeviceId: 'phone', state: phoneState(), ...over });
}
const phoneSays = (state: PlaybackState, serverMs = NOW + 500) => handler({ type: 'state', serverMs, from: 'phone', state });

beforeEach(() => {
  vi.useFakeTimers({ toFake: ['Date', 'setTimeout', 'clearTimeout', 'setInterval', 'clearInterval'], now: NOW });
  sent.length = 0;
  loads.length = 0;
  clientOk = true;
  localStorage.clear();
  mirrorPlayer.leave(true);
  usePlayer.setState({ queue: [], index: -1, nowPlaying: null, status: 'idle', shuffle: false, repeat: 'off', autoplay: false, volume: 0.8, muted: false });
});
afterEach(() => {
  stop?.();
  stop = null;
  vi.useRealTimers();
});

describe('mirror mode', () => {
  it('shows the phone’s session from the welcome: queue, song, status, position, shuffle, repeat, and its volume apart from ours', async () => {
    await join({ state: phoneState({ shuffle: true, repeat: 'all' }) });
    const s = usePlayer.getState();
    expect(s.mirror).toBe(true);
    expect(ids()).toEqual(['a', 'b', 'c', 'd']);
    expect(s.index).toBe(1);
    expect(s.nowPlaying).toMatchObject({ videoId: 'b', title: 'Tb', artist: 'Art', durationSec: 200, medium: null });
    expect(s.status).toBe('playing');
    expect(s).toMatchObject({ shuffle: true, repeat: 'all', mirrorVolume: 0.4, volume: 0.8 });
    expect(loads).toHaveLength(0);
    // The welcome is the phone's: this browser did nothing, and claims nothing.
    expect(sent.some((m) => m.type === 'claim')).toBe(false);
  });

  it('follows the phone’s next states in place, with no claim from a change it did not make', async () => {
    await join();
    const before = usePlayer.getState().queue;
    phoneSays(phoneState({ index: 2, positionMs: 1000, isPlaying: false }));
    const s = usePlayer.getState();
    expect(s.index).toBe(2);
    expect(s.status).toBe('paused');
    expect(s.queue.map((e) => e.entryId)).toEqual(before.map((e) => e.entryId));
    phoneSays(phoneState({ index: 3, isPlaying: true, queue: [ct('a'), ct('b'), ct('c'), ct('d'), ct('e')] }));
    expect(ids()).toEqual(['a', 'b', 'c', 'd', 'e']);
    expect(sent.some((m) => m.type === 'claim' || m.type === 'state')).toBe(false);
    expect(useConnect.getState().activeId).toBe('phone');
  });

  it('getPlayhead() is the phone’s position carried forward by the clock, and holds while paused', async () => {
    await join({ stateServerMs: NOW });
    expect(getPlayhead()).toEqual({ sec: 30, paused: false });
    vi.setSystemTime(NOW + 4_000);
    expect(getPlayhead()?.sec).toBeCloseTo(34, 1);
    phoneSays(phoneState({ positionMs: 50_000, isPlaying: false }), NOW + 4_000);
    vi.setSystemTime(NOW + 9_000);
    expect(getPlayhead()).toEqual({ sec: 50, paused: true });
  });

  it('does not enter the mirror for a session with nothing in it, and leaves it when the active device is gone', async () => {
    await join({ state: phoneState({ queue: [], index: -1 }) });
    expect(usePlayer.getState().mirror).toBe(false);
    phoneSays(phoneState());
    expect(usePlayer.getState().mirror).toBe(true);
    handler({ type: 'devices', serverMs: NOW + 1, devices, activeDeviceId: null });
    const s = usePlayer.getState();
    expect(s.mirror).toBe(false);
    // What was shown stays, as a paused queue; nothing plays.
    expect(s.status).toBe('paused');
    expect(ids()).toEqual(['a', 'b', 'c', 'd']);
    expect(loads).toHaveLength(0);
  });

  it('a song of its own that no one has claimed keeps the floor over the phone’s state', async () => {
    usePlayer.getState().playTracks([track('mine')], 0, SOURCE);
    await flush();
    usePlayer.setState({ status: 'playing' });
    await join();
    expect(usePlayer.getState().mirror).toBe(false);
    expect(ids()).toEqual(['mine']);
    // …and it claims the session, as it did before the mirror existed.
    expect(sent.some((m) => m.type === 'claim')).toBe(true);
  });
});

describe('controls in mirror mode are commands to the phone', () => {
  beforeEach(() => join());

  it('every transport control sends its command and answers at once', () => {
    const p = usePlayer.getState();
    p.togglePlay();
    expect(last()).toEqual({ action: 'toggle' });
    expect(usePlayer.getState().status).toBe('paused');
    p.next();
    expect(last()).toEqual({ action: 'next' });
    expect(usePlayer.getState().index).toBe(2);
    vi.setSystemTime(NOW + 100);
    p.previous();
    expect(last()).toEqual({ action: 'previous' });
    expect(usePlayer.getState().index).toBe(1);
    p.seek(75.4);
    expect(last()).toEqual({ action: 'seek', value: 75_400 });
    expect(getPlayhead()?.sec).toBeCloseTo(75.4, 1);
    p.toggleShuffle();
    expect(last()).toEqual({ action: 'shuffle', value: true });
    expect(usePlayer.getState().shuffle).toBe(true);
    p.cycleRepeat();
    expect(last()).toEqual({ action: 'repeat', value: 'all' });
    expect(usePlayer.getState().repeat).toBe('all');
    p.jumpTo(3);
    expect(last()).toEqual({ action: 'jump', value: 3 });
    expect(usePlayer.getState().index).toBe(3);
  });

  it('the volume control drives the phone’s volume and leaves the local one', () => {
    usePlayer.getState().setVolume(0.9);
    expect(last()).toEqual({ action: 'volume', value: 0.9 });
    expect(usePlayer.getState()).toMatchObject({ mirrorVolume: 0.9, volume: 0.8 });
    usePlayer.getState().toggleMute();
    expect(last()).toEqual({ action: 'volume', value: 0 });
    expect(usePlayer.getState().mirrorVolume).toBe(0);
    usePlayer.getState().toggleMute();
    expect(last()).toEqual({ action: 'volume', value: 0.9 });
    expect(JSON.parse(localStorage.getItem('bitchord:player') ?? '{}').volume ?? 0.8).toBe(0.8);
  });

  it('every queue edit is a command, with only the songs YouTube has', () => {
    const p = usePlayer.getState();
    p.playNext([track('n1'), track('auden:local:9', { source: 'local' }), track('n2')]);
    expect(last()).toEqual({ action: 'playNext', tracks: [expect.objectContaining({ videoId: 'n1', title: 'Tn1', artist: 'Art' }), expect.objectContaining({ videoId: 'n2' })] });
    expect(ids()).toEqual(['a', 'b', 'n1', 'n2', 'c', 'd']);
    p.addToQueue([track('z')]);
    expect(last()).toEqual({ action: 'addToQueue', tracks: [expect.objectContaining({ videoId: 'z' })] });
    expect(ids().at(-1)).toBe('z');
    p.removeAt(2);
    expect(last()).toEqual({ action: 'remove', value: 2 });
    expect(ids()).toEqual(['a', 'b', 'n2', 'c', 'd', 'z']);
    p.move(2, 4);
    expect(last()).toEqual({ action: 'move', value: 2, to: 4 });
    expect(ids()).toEqual(['a', 'b', 'c', 'd', 'n2', 'z']);
    p.clearUpNext();
    expect(last()).toEqual({ action: 'clearUpcoming' });
    expect(ids()).toEqual(['a', 'b']);
    // The playing song cannot be removed, and a local-only list sends nothing at all.
    const n = commands().length;
    p.removeAt(1);
    p.playNext([track('auden:local:1', { source: 'local' })]);
    expect(commands()).toHaveLength(n + 1);
    expect(last()).toEqual({ action: 'remove', value: 1 });
    expect(ids()).toEqual(['a', 'b']);
  });

  it('the phone’s next state corrects a guess', () => {
    usePlayer.getState().removeAt(3);
    expect(ids()).toEqual(['a', 'b', 'c']);
    phoneSays(phoneState());
    expect(ids()).toEqual(['a', 'b', 'c', 'd']);
  });

  it('sends nothing, and keeps what it shows, when the socket is down', () => {
    clientOk = false;
    usePlayer.getState().togglePlay();
    expect(usePlayer.getState().status).toBe('playing');
    expect(remoteCommand({ action: 'next' })).toBe(false);
  });
});

describe('starting something here leaves the mirror and claims the session', () => {
  beforeEach(() => join());

  it('playTracks releases the phone, plays here and is not pulled back by the phone’s old states', async () => {
    usePlayer.getState().playTracks([track('x1'), track('x2')], 1, SOURCE);
    await flush();
    const s = usePlayer.getState();
    expect(s.mirror).toBe(false);
    expect(ids()).toEqual(['x1', 'x2']);
    expect(loads.at(-1)?.id).toBe('x2');
    const claims = sent.filter((m) => m.type === 'claim');
    expect(claims).toHaveLength(1);
    expect(useConnect.getState()).toMatchObject({ activeId: 'web1', remote: null });
    // The phone is released by the server: a state of its own still in flight changes nothing here.
    phoneSays(phoneState({ index: 2 }));
    expect(usePlayer.getState().mirror).toBe(false);
    expect(ids()).toEqual(['x1', 'x2']);
    // And what plays here is published as the session.
    usePlayer.setState({ status: 'playing' });
    vi.advanceTimersByTime(200);
    const state = sent.filter((m): m is Extract<ClientMessage, { type: 'state' }> => m.type === 'state').at(-1)!.state;
    expect(state.queue.map((t) => t.videoId)).toEqual(['x1', 'x2']);
    expect(state.index).toBe(1);
  });

  it('a local file claims too: the phone is released and the session says nothing is queued', async () => {
    usePlayer.getState().playTracks([track('auden:local:1', { source: 'local' })], 0, SOURCE);
    await flush();
    expect(usePlayer.getState().mirror).toBe(false);
    const claim = sent.find((m): m is Extract<ClientMessage, { type: 'claim' }> => m.type === 'claim');
    expect(claim?.state).toMatchObject({ queue: [], index: -1, isPlaying: false });
  });

  it('playTrack and a DJ start also end it', async () => {
    usePlayer.getState().playTrack(track('solo'), SOURCE);
    await flush();
    expect(usePlayer.getState().mirror).toBe(false);
    expect(sent.filter((m) => m.type === 'claim')).toHaveLength(1);
  });
});

describe('switching', () => {
  it('picking this browser while mirroring transfers to it; the takeover loads the same queue, song and position, and the mirror ends', async () => {
    await join({ state: phoneState({ index: 2, positionMs: 61_500 }) });
    sent.length = 0;
    switchTo('web1');
    expect(sent).toEqual([{ type: 'transfer', to: 'web1', play: true }]);
    expect(usePlayer.getState().mirror).toBe(true);
    handler({ type: 'takeover', serverMs: NOW + 2_000, play: true, state: phoneState({ index: 2, positionMs: 62_000 }) });
    await flush();
    const s = usePlayer.getState();
    expect(s.mirror).toBe(false);
    expect(ids()).toEqual(['a', 'b', 'c', 'd']);
    expect(s.index).toBe(2);
    expect(loads).toEqual([{ id: 'c', startSec: 62, autoplay: true }]);
    expect(useConnect.getState()).toMatchObject({ activeId: 'web1', remote: null });
    // Taking the session over is not a claim, and the local volume is still ours.
    expect(sent.some((m) => m.type === 'claim')).toBe(false);
    expect(s.volume).toBe(0.8);
  });

  it('picking another device while playing here sends the fresh state, then the transfer', async () => {
    await join({ activeDeviceId: null, state: null });
    handler({ type: 'takeover', serverMs: NOW, play: true, state: phoneState() });
    await flush();
    usePlayer.setState({ status: 'playing' });
    sent.length = 0;
    switchTo('phone');
    expect(sent.map((m) => m.type)).toEqual(['state', 'transfer']);
    expect(sent[1]).toEqual({ type: 'transfer', to: 'phone', play: true });
  });

  it('released: the queue stays and this browser becomes a mirror of what it was playing, from where it was', async () => {
    await join({ activeDeviceId: null, state: null });
    handler({ type: 'takeover', serverMs: NOW, play: true, state: phoneState({ index: 1, positionMs: 20_000 }) });
    await flush();
    usePlayer.setState({ status: 'playing' });
    const before = usePlayer.getState().queue.map((e) => e.track.videoId);
    handler({ type: 'release', serverMs: NOW + 1, to: 'phone' });
    const s = usePlayer.getState();
    expect(s.mirror).toBe(true);
    expect(s.queue.map((e) => e.track.videoId)).toEqual(before);
    expect(s.index).toBe(1);
    expect(s.status).toBe('playing');
    expect(useConnect.getState().activeId).toBe('phone');
    // The element's own pause event arriving later does not change what the mirror says.
    usePlayer.setState({ status: 'playing' });
    expect(sent.filter((m) => m.type === 'claim')).toHaveLength(0);
    // The phone's state then replaces it in place.
    phoneSays(phoneState({ index: 2, positionMs: 3000 }));
    expect(usePlayer.getState().index).toBe(2);
  });
});

describe('shuffle’s order', () => {
  it('publishes the pre-shuffle order while shuffle is on, and none when it is off', async () => {
    await join({ activeDeviceId: null, state: null });
    usePlayer.getState().playTracks(['a', 'b', 'c', 'd', 'e'].map((i) => track(i)), 0, SOURCE);
    await flush();
    usePlayer.setState({ status: 'playing' });
    expect(localState()?.order).toBeUndefined();
    usePlayer.getState().toggleShuffle();
    const state = localState()!;
    expect(state.shuffle).toBe(true);
    expect(state.order).toEqual(['a', 'b', 'c', 'd', 'e']);
    expect([...state.queue.map((t) => t.videoId)].sort()).toEqual(['a', 'b', 'c', 'd', 'e']);
    usePlayer.getState().toggleShuffle();
    expect(localState()?.order).toBeUndefined();
    expect(localState()?.queue.map((t) => t.videoId)).toEqual(['a', 'b', 'c', 'd', 'e']);
  });

  it('adopts the order on a takeover, so shuffle off (here, or by command) lands on the same list', async () => {
    await join({ activeDeviceId: null, state: null });
    handler({ type: 'takeover', serverMs: NOW, play: true, state: phoneState({ queue: ['a', 'd', 'c', 'e', 'b'].map((i) => ct(i)), index: 0, shuffle: true, order: ['a', 'b', 'c', 'd', 'e'] }) });
    await flush();
    usePlayer.setState({ status: 'playing' });
    expect(localState()?.order).toEqual(['a', 'b', 'c', 'd', 'e']);
    handler({ type: 'command', serverMs: NOW + 1, from: 'phone', command: { action: 'shuffle', value: false } });
    expect(ids()).toEqual(['a', 'b', 'c', 'd', 'e']);
    expect(usePlayer.getState().shuffle).toBe(false);
  });

  it('a mirror turning shuffle off lands on the order the phone sent', async () => {
    await join({ state: phoneState({ queue: ['a', 'd', 'c', 'e', 'b'].map((i) => ct(i)), index: 0, shuffle: true, order: ['a', 'b', 'c', 'd', 'e'] }) });
    usePlayer.getState().toggleShuffle();
    expect(last()).toEqual({ action: 'shuffle', value: false });
    expect(ids()).toEqual(['a', 'b', 'c', 'd', 'e']);
    expect(usePlayer.getState().shuffle).toBe(false);
  });
});

describe('this browser as the playing device answers the other one’s commands', () => {
  beforeEach(async () => {
    await join({ activeDeviceId: null, state: null });
    handler({ type: 'takeover', serverMs: NOW, play: true, state: phoneState({ index: 0, positionMs: 0 }) });
    await flush();
    usePlayer.setState({ status: 'playing' });
    sent.length = 0;
    usePlayer.getState().addToQueue([]); // a no-op: just to publish the indices below from a known state
    void localState();
  });
  const command = (c: Command) => {
    handler({ type: 'command', serverMs: NOW + 1, from: 'phone', command: c });
    vi.advanceTimersByTime(200);
  };
  const published = () => sent.filter((m): m is Extract<ClientMessage, { type: 'state' }> => m.type === 'state').at(-1)?.state;

  it('queue edits apply to the queue and are published as the new state', () => {
    command({ action: 'playNext', tracks: [ct('n')] });
    expect(ids()).toEqual(['a', 'n', 'b', 'c', 'd']);
    expect(published()?.queue.map((t) => t.videoId)).toEqual(['a', 'n', 'b', 'c', 'd']);
    command({ action: 'addToQueue', tracks: [ct('z')] });
    // Behind the songs queued by hand, ahead of the list playing: the player's own rule.
    expect(ids()).toEqual(['a', 'n', 'z', 'b', 'c', 'd']);
    void localState();
    // Indices are the phone's last view: [a n z b c d]
    command({ action: 'remove', value: 2 });
    expect(ids()).toEqual(['a', 'n', 'b', 'c', 'd']);
    void localState();
    command({ action: 'move', value: 4, to: 1 });
    expect(ids()).toEqual(['a', 'd', 'n', 'b', 'c']);
    void localState();
    command({ action: 'clearUpcoming' });
    expect(ids()).toEqual(['a']);
    expect(published()?.queue.map((t) => t.videoId)).toEqual(['a']);
  });

  it('shuffle uses the player’s own shuffle and publishes the order', () => {
    command({ action: 'shuffle', value: true });
    expect(usePlayer.getState().shuffle).toBe(true);
    expect(published()?.order).toEqual(['a', 'b', 'c', 'd']);
    command({ action: 'shuffle', value: false });
    expect(ids()).toEqual(['a', 'b', 'c', 'd']);
    expect(published()?.order).toBeUndefined();
  });

  it('repeat and jump follow the phone’s numbering', () => {
    command({ action: 'repeat', value: 'one' });
    expect(usePlayer.getState().repeat).toBe('one');
    command({ action: 'jump', value: 2 });
    expect(usePlayer.getState().index).toBe(2);
  });
});
