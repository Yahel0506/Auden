// @vitest-environment happy-dom
import { beforeEach, describe, expect, it, vi } from 'vitest';
import type { ClientMessage, PlaybackState, ServerMessage } from '@bitchord/shared/connect';

// The pieces the session leans on, replaced by ones a test can read and drive.
const sent: ClientMessage[] = [];
let handler: (m: ServerMessage) => void = () => {};
let setStatus: (s: 'off' | 'connecting' | 'online') => void = () => {};

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
      sent.push(m);
      return true;
    }
  },
}));

const load = vi.fn(async () => {});
const pause = vi.fn();
const play = vi.fn();
const seekTo = vi.fn();
const setVolume = vi.fn();
const next = vi.fn();
const togglePlay = vi.fn();
const jumpTo = vi.fn();

type Listener = (s: Record<string, unknown>, before: Record<string, unknown>) => void;
let listener: Listener = () => {};
let playerState: Record<string, unknown>;
const usePlayer = Object.assign(() => playerState, {
  getState: () => playerState,
  setState: (patch: Record<string, unknown>) => {
    const before = playerState;
    playerState = { ...playerState, ...patch };
    listener(playerState, before);
  },
  subscribe: (l: Listener) => {
    listener = l;
    return () => {};
  },
});
const show = vi.fn();
const leave = vi.fn();
vi.mock('@/state/player', () => ({
  usePlayer,
  partyPlayer: { load, pause, play, seekTo },
  getPlayhead: () => ({ sec: 12, paused: false }),
  isMirrored: () => !!playerState.mirror,
  mirrorPlayer: { show, leave },
  setMirrorHooks: vi.fn(),
  adoptShuffleOrder: vi.fn(),
  canonicalOrderOf: (entries: unknown[]) => entries,
}));
vi.mock('@/playback/source', () => ({ isShareable: (t: { source?: string }) => t.source !== 'local' }));

const track = (id: string, source?: string) => ({ videoId: id, title: `T${id}`, artists: [{ name: 'X', id: null }], album: null, durationSec: 200, explicit: false, thumbnail: null, index: null, plays: null, source });
const entry = (id: string, source?: string) => ({ entryId: `e${id}`, track: track(id, source), tier: 'user', source: null });
const base = () => ({
  queue: [entry('a'), entry('b')], index: 0, nowPlaying: { videoId: 'a', source: 'youtube' }, status: 'paused', shuffle: false, repeat: 'off', volume: 0.7, muted: false,
  setVolume, next, togglePlay, jumpTo, previous: vi.fn(),
});
const remoteState = (over: Partial<PlaybackState> = {}): PlaybackState => ({
  queue: [{ videoId: 'x1', title: 'One', artist: 'A', durationSec: 100 }, { videoId: 'x2', title: 'Two', artist: 'B' }],
  index: 0, positionMs: 4000, isPlaying: true, volume: 0.4, shuffle: true, repeat: 'all', ...over,
});

const { startConnect, switchTo, remoteCommand } = await import('./session');
const { useConnect } = await import('./store');

async function join(over: Partial<Extract<ServerMessage, { type: 'welcome' }>> = {}) {
  sent.length = 0;
  const stop = await startConnect();
  setStatus('online');
  handler({
    type: 'welcome', serverMs: 10_000, stateServerMs: 9_000, devices: [
      { deviceId: 'web1', name: 'Chrome on Linux', platform: 'web', online: true },
      { deviceId: 'phone', name: 'Pixel', platform: 'android', online: true },
    ], activeDeviceId: null, state: null, ...over,
  });
  return stop;
}

beforeEach(() => {
  playerState = base();
  for (const f of [load, pause, play, seekTo, setVolume, next, togglePlay, jumpTo, show, leave]) f.mockClear();
  sent.length = 0;
});

describe('Auden Connect, this browser', () => {
  it('lists the devices from the welcome and shows another device as the one playing', async () => {
    const stop = await join({ activeDeviceId: 'phone', state: remoteState() });
    const s = useConnect.getState();
    expect(s.devices.map((d) => d.deviceId)).toEqual(['web1', 'phone']);
    expect(s.activeId).toBe('phone');
    expect(s.remote?.state.queue[0]?.title).toBe('One');
    stop();
  });

  it('claims the session when this browser starts playing, and says what it plays', async () => {
    const stop = await join({ activeDeviceId: 'phone', state: remoteState() });
    sent.length = 0;
    usePlayer.setState({ status: 'playing' });
    const claim = sent.find((m) => m.type === 'claim');
    expect(claim).toMatchObject({ type: 'claim', state: { index: 0, isPlaying: true, positionMs: 12_000, volume: 0.7 } });
    expect(useConnect.getState().activeId).toBe('web1');
    expect(useConnect.getState().remote).toBeNull();
    stop();
  });

  it('does not claim for a party queue or for a local file', async () => {
    const stop = await join();
    playerState = { ...base(), queue: [{ ...entry('a'), source: { title: 'Listen Together' } }] };
    usePlayer.setState({ status: 'playing' });
    expect(sent.some((m) => m.type === 'claim')).toBe(false);
    playerState = { ...base(), queue: [entry('a', 'local')] };
    usePlayer.setState({ status: 'paused' });
    usePlayer.setState({ status: 'playing' });
    expect(sent.some((m) => m.type === 'claim')).toBe(false);
    stop();
  });

  it('takes over: loads the other device’s queue at its position and plays', async () => {
    const stop = await join({ activeDeviceId: 'phone', state: remoteState() });
    handler({ type: 'takeover', serverMs: 11_000, play: true, state: remoteState({ index: 1, positionMs: 7500 }) });
    expect(load).toHaveBeenCalledTimes(1);
    const [tracks, tiers, index, startSec, play] = load.mock.calls[0] as unknown as [{ videoId: string }[], string[], number, number, boolean];
    expect(tracks.map((t) => t.videoId)).toEqual(['x1', 'x2']);
    expect(tiers).toEqual(['context', 'context']);
    expect([index, startSec, play]).toEqual([1, 7.5, true]);
    // The volume is this device's own: the phone's level does not come with the session.
    expect(setVolume).not.toHaveBeenCalled();
    expect(playerState).toMatchObject({ shuffle: true, repeat: 'all' });
    expect(useConnect.getState().activeId).toBe('web1');
    stop();
  });

  it('is released: pauses its own player and becomes a remote showing what it was playing', async () => {
    const stop = await join();
    handler({ type: 'takeover', serverMs: 11_000, play: true, state: remoteState() });
    playerState = { ...base(), status: 'playing' };
    handler({ type: 'release', serverMs: 12_000, to: 'phone' });
    expect(pause).toHaveBeenCalled();
    expect(useConnect.getState().activeId).toBe('phone');
    expect(useConnect.getState().remote?.state.queue[0]?.videoId).toBe('a');
    stop();
  });

  it('does what the remote asks while it is the active device, and ignores it otherwise', async () => {
    const stop = await join({ activeDeviceId: 'phone', state: remoteState() });
    handler({ type: 'command', serverMs: 1, from: 'phone', command: { action: 'next' } });
    expect(next).not.toHaveBeenCalled();
    handler({ type: 'takeover', serverMs: 2, play: true, state: remoteState() });
    handler({ type: 'command', serverMs: 3, from: 'phone', command: { action: 'next' } });
    handler({ type: 'command', serverMs: 4, from: 'phone', command: { action: 'seek', value: 30_000 } });
    handler({ type: 'command', serverMs: 5, from: 'phone', command: { action: 'volume', value: 0.2 } });
    expect(next).toHaveBeenCalledTimes(1);
    expect(seekTo).toHaveBeenCalledWith(30);
    expect(setVolume).toHaveBeenLastCalledWith(0.2);
    stop();
  });

  it('as a remote, sends commands and answers at once', async () => {
    const stop = await join({ activeDeviceId: 'phone', state: remoteState({ isPlaying: true }) });
    expect(remoteCommand({ action: 'toggle' })).toBe(true);
    expect(sent.at(-1)).toEqual({ type: 'command', command: { action: 'toggle' } });
    expect(useConnect.getState().remote?.state.isPlaying).toBe(false);
    remoteCommand({ action: 'volume', value: 0.9 });
    expect(useConnect.getState().remote?.state.volume).toBe(0.9);
    stop();
  });

  it('has no remote to drive while it is the active device', async () => {
    const stop = await join();
    handler({ type: 'takeover', serverMs: 2, play: true, state: remoteState() });
    expect(remoteCommand({ action: 'next' })).toBe(false);
    stop();
  });

  it('switching to another device from here sends the fresh state first, then the transfer', async () => {
    const stop = await join();
    handler({ type: 'takeover', serverMs: 2, play: true, state: remoteState() });
    playerState = { ...base(), status: 'playing' };
    sent.length = 0;
    switchTo('phone');
    expect(sent.map((m) => m.type)).toEqual(['state', 'transfer']);
    expect(sent[1]).toEqual({ type: 'transfer', to: 'phone', play: true });
    stop();
  });

  it('switching back to this browser asks for the session, keeping it playing if it was', async () => {
    const stop = await join({ activeDeviceId: 'phone', state: remoteState({ isPlaying: false }) });
    sent.length = 0;
    switchTo('web1');
    expect(sent).toEqual([{ type: 'transfer', to: 'web1', play: false }]);
    stop();
  });

  it('a reload that finds the room still calling it the active device re-states what it holds', async () => {
    const stop = await join({ activeDeviceId: 'web1', state: remoteState() });
    expect(sent.some((m) => m.type === 'state')).toBe(true);
    stop();
  });

  it('a server that drops the active device leaves no remote to show', async () => {
    const stop = await join({ activeDeviceId: 'phone', state: remoteState() });
    handler({ type: 'devices', serverMs: 5, devices: [{ deviceId: 'web1', name: 'x', platform: 'web', online: true }], activeDeviceId: null });
    expect(useConnect.getState().remote).toBeNull();
    stop();
  });
});
