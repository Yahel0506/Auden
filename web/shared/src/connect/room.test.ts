import { describe, expect, it } from 'vitest';
import { ACTIVE_GRACE_MS, CONNECT_PROTOCOL, parseClientMessage, playheadMs, type ClientMessage, type ConnectDevice, type PlaybackState } from './protocol';
import { emptyRoom, onClose, onHello, onMessage, onTick, type RoomState, type Step } from './room';

const dev = (id: string, platform: ConnectDevice['platform'] = 'web'): ConnectDevice => ({ deviceId: id, name: `Device ${id}`, platform });
const hello = (id: string): Extract<ClientMessage, { type: 'hello' }> => ({ type: 'hello', v: CONNECT_PROTOCOL, device: dev(id) });
const state = (over: Partial<PlaybackState> = {}): PlaybackState => ({
  queue: [{ videoId: 'aaaaaaaaaaa', title: 'A', artist: 'X' }, { videoId: 'bbbbbbbbbbb', title: 'B', artist: 'X' }],
  index: 0, positionMs: 1000, isPlaying: true, volume: 0.5, shuffle: false, repeat: 'off', ...over,
});

/** A little harness: the set of online devices follows the steps taken. */
function world() {
  let room: RoomState = emptyRoom();
  const online = new Set<string>();
  const sent: Step['out'] = [];
  const apply = (s: Step) => { room = s.room; sent.push(...s.out); return s; };
  return {
    get room() { return room; },
    online, sent,
    join(id: string, now = 0) { online.add(id); return apply(onHello(room, hello(id), online, now)); },
    leave(id: string, now = 0) { online.delete(id); return apply(onClose(room, id, online, now)); },
    send(from: string, msg: ClientMessage, now = 0) { return apply(onMessage(room, from, msg, online, now)); },
    tick(now: number) { return apply(onTick(room, online, now)); },
  };
}

describe('the room', () => {
  it('tells a new device who is online and what is playing, and tells the others about it', () => {
    const w = world();
    w.join('phone');
    w.send('phone', { type: 'claim', state: state() }, 100);
    const step = w.join('web', 200);
    const welcome = step.out.find((o) => 'to' in o && o.to === 'web')!.message;
    expect(welcome).toMatchObject({ type: 'welcome', activeDeviceId: 'phone', stateServerMs: 100 });
    expect((welcome as { devices: unknown[] }).devices).toHaveLength(2);
    expect(step.out.some((o) => 'all' in o && o.except === 'web' && o.message.type === 'devices')).toBe(true);
  });

  it('claim makes the device active and releases the previous one', () => {
    const w = world();
    w.join('phone'); w.join('web');
    w.send('phone', { type: 'claim', state: state() });
    const step = w.send('web', { type: 'claim', state: state({ positionMs: 9000 }) });
    expect(w.room.activeDeviceId).toBe('web');
    expect(step.out).toContainEqual({ to: 'phone', message: { type: 'release', serverMs: 0, to: 'web' } });
  });

  it('only the active device speaks for the session; anyone else is told to be a remote', () => {
    const w = world();
    w.join('phone'); w.join('web');
    w.send('phone', { type: 'claim', state: state() });
    const step = w.send('web', { type: 'state', state: state({ positionMs: 77 }) });
    expect(w.room.state?.positionMs).toBe(1000);
    expect(step.out).toEqual([{ to: 'web', message: { type: 'release', serverMs: 0, to: 'phone' } }]);
  });

  it('transfer takes the state to the target at the moment of the hand-over and releases the old device', () => {
    const w = world();
    w.join('phone'); w.join('web');
    w.send('phone', { type: 'claim', state: state({ positionMs: 1000 }) }, 1000);
    const step = w.send('web', { type: 'transfer', to: 'web', play: true }, 5000);
    expect(w.room.activeDeviceId).toBe('web');
    const takeover = step.out.find((o) => 'to' in o && o.to === 'web')!.message;
    // 1000 ms said at t=1000 while playing, and 4000 ms went by.
    expect(takeover).toMatchObject({ type: 'takeover', play: true, state: { positionMs: 5000 } });
    expect(step.out).toContainEqual({ to: 'phone', message: { type: 'release', serverMs: 5000, to: 'web' } });
  });

  it('a paused state does not advance during a transfer', () => {
    const w = world();
    w.join('phone'); w.join('web');
    w.send('phone', { type: 'claim', state: state({ isPlaying: false, positionMs: 3000 }) }, 0);
    const step = w.send('web', { type: 'transfer', to: 'web', play: false }, 60_000);
    expect(step.out.find((o) => 'to' in o && o.to === 'web')!.message).toMatchObject({ state: { positionMs: 3000 } });
  });

  it('refuses a transfer to a device that is not online', () => {
    const w = world();
    w.join('phone'); w.join('web');
    w.leave('web');
    const step = w.send('phone', { type: 'transfer', to: 'web', play: true });
    expect(step.out).toEqual([{ to: 'phone', message: { type: 'error', code: 'unknown_device' } }]);
    expect(w.room.activeDeviceId).toBeNull();
  });

  it('routes a command to the active device, or to the named one, and says when there is none', () => {
    const w = world();
    w.join('phone'); w.join('web');
    expect(w.send('web', { type: 'command', command: { action: 'pause' } }).out).toEqual([{ to: 'web', message: { type: 'error', code: 'no_active' } }]);
    w.send('phone', { type: 'claim', state: state() });
    expect(w.send('web', { type: 'command', command: { action: 'next' } }).out).toEqual([
      { to: 'phone', message: { type: 'command', serverMs: 0, from: 'web', command: { action: 'next' } } },
    ]);
  });

  it('keeps the session through a dropped connection and lets it go after the grace period', () => {
    const w = world();
    w.join('phone'); w.join('web');
    w.send('phone', { type: 'claim', state: state() }, 0);
    w.leave('phone', 1000);
    expect(w.tick(1000 + ACTIVE_GRACE_MS - 1).out).toEqual([]);
    expect(w.room.activeDeviceId).toBe('phone');
    w.tick(1000 + ACTIVE_GRACE_MS);
    expect(w.room.activeDeviceId).toBeNull();
    expect(w.room.state?.isPlaying).toBe(false);
  });

  it('a reconnecting active device keeps the session', () => {
    const w = world();
    w.join('phone'); w.send('phone', { type: 'claim', state: state() }, 0);
    w.leave('phone', 1000);
    w.join('phone', 5000);
    w.tick(1000 + ACTIVE_GRACE_MS + 1);
    expect(w.room.activeDeviceId).toBe('phone');
    expect(w.room.activeLostAtMs).toBeNull();
  });

  it('refuses a ninth device but forgets an offline one to make room', () => {
    const w = world();
    for (let i = 0; i < 8; i++) w.join(`d${i}`);
    expect(w.join('extra').reject).toBe('extra');
    w.leave('d3');
    const step = w.join('extra2');
    expect(step.reject).toBeUndefined();
    expect(w.room.devices.d3).toBeUndefined();
  });

  it('rejects another protocol version', () => {
    const w = world();
    w.online.add('x');
    const step = onHello(w.room, { type: 'hello', v: 99, device: dev('x') }, w.online, 0);
    expect(step.reject).toBe('x');
  });
});

describe('parseClientMessage', () => {
  it('accepts a good state and rejects limits and garbage', () => {
    expect(parseClientMessage(JSON.stringify({ type: 'state', state: state() }))).not.toBeNull();
    expect(parseClientMessage('nope')).toBeNull();
    expect(parseClientMessage(JSON.stringify({ type: 'state', state: state({ index: 5 }) }))).toBeNull();
    expect(parseClientMessage(JSON.stringify({ type: 'state', state: { ...state(), queue: Array(101).fill(state().queue[0]) } }))).toBeNull();
    expect(parseClientMessage(JSON.stringify({ type: 'command', command: { action: 'seek' } }))).toBeNull();
    expect(parseClientMessage(JSON.stringify({ type: 'command', command: { action: 'volume', value: 7 } }))).toMatchObject({ command: { value: 1 } });
    expect(parseClientMessage(JSON.stringify({ type: 'hello', v: 1, device: { deviceId: 'a', name: 'n', platform: 'toaster' } }))).toBeNull();
  });

  it('keeps the unshuffled order only while shuffle is on, and checks it', () => {
    const on = parseClientMessage(JSON.stringify({ type: 'state', state: state({ shuffle: true, order: ['bbbbbbbbbbb', 'aaaaaaaaaaa'] }) }));
    expect(on && on.type === 'state' && on.state.order).toEqual(['bbbbbbbbbbb', 'aaaaaaaaaaa']);
    const off = parseClientMessage(JSON.stringify({ type: 'state', state: state({ shuffle: false, order: ['x'] }) }));
    expect(off && off.type === 'state' && off.state.order).toBeUndefined();
    expect(parseClientMessage(JSON.stringify({ type: 'state', state: state({ shuffle: true, order: [1] as unknown as string[] }) }))).toBeNull();
  });

  it('parses the queue-editing commands and rejects malformed ones', () => {
    const t1 = { videoId: 'ccccccccccc', title: 'C', artist: 'X' };
    expect(parseClientMessage(JSON.stringify({ type: 'command', command: { action: 'playNext', tracks: [t1] } }))).toMatchObject({ command: { action: 'playNext', tracks: [t1] } });
    expect(parseClientMessage(JSON.stringify({ type: 'command', command: { action: 'addToQueue', tracks: [] } }))).toBeNull();
    expect(parseClientMessage(JSON.stringify({ type: 'command', command: { action: 'move', value: 2, to: 0 } }))).toMatchObject({ command: { action: 'move', value: 2, to: 0 } });
    expect(parseClientMessage(JSON.stringify({ type: 'command', command: { action: 'move', value: 2 } }))).toBeNull();
    expect(parseClientMessage(JSON.stringify({ type: 'command', command: { action: 'remove', value: -1 } }))).toBeNull();
    expect(parseClientMessage(JSON.stringify({ type: 'command', command: { action: 'clearUpcoming' } }))).toMatchObject({ command: { action: 'clearUpcoming' } });
  });

  it('drops an artwork URL that is not http(s)', () => {
    const s = state();
    s.queue[0]!.artworkUrl = 'javascript:alert(1)';
    const parsed = parseClientMessage(JSON.stringify({ type: 'state', state: s }));
    expect(parsed && parsed.type === 'state' && parsed.state.queue[0]!.artworkUrl).toBeUndefined();
  });
});

describe('playheadMs', () => {
  it('runs while playing and holds while paused, never past the end', () => {
    expect(playheadMs(state({ positionMs: 1000 }), 100, 2100)).toBe(3000);
    expect(playheadMs(state({ positionMs: 1000, isPlaying: false }), 100, 2100)).toBe(1000);
    expect(playheadMs(state({ positionMs: 1000 }), 100, 99_100, 5000)).toBe(5000);
  });
});
