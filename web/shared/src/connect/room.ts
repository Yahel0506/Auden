import {
  ACTIVE_GRACE_MS,
  CONNECT_PROTOCOL,
  MAX_DEVICES,
  type ClientMessage,
  type ConnectDevice,
  type DeviceEntry,
  type PlaybackState,
  type ServerMessage,
} from './protocol';

/**
 * One account's room, as pure data and pure steps: the Worker's Durable Object owns the sockets and the
 * storage, and calls these with who is online and what time it is. Nothing here touches a socket.
 */
export interface RoomState {
  /** Everyone who has said hello, online or not (a phone that dropped is still listed, greyed). */
  devices: Record<string, ConnectDevice>;
  /** The device making sound, or null. */
  activeDeviceId: string | null;
  /** What the active device last said, and the server time it said it. */
  state: PlaybackState | null;
  stateServerMs: number;
  /** When the active device was first seen offline (null while online): the session lives ACTIVE_GRACE_MS. */
  activeLostAtMs: number | null;
}

export const emptyRoom = (): RoomState => ({ devices: {}, activeDeviceId: null, state: null, stateServerMs: 0, activeLostAtMs: null });

/** A frame to send: to one device, or to every online device but [except]. */
export type Outbound = { to: string; message: ServerMessage } | { all: true; except?: string; message: ServerMessage };

export interface Step {
  room: RoomState;
  out: Outbound[];
  /** The device that must be dropped now (a second hello for a full room). */
  reject?: string;
}

const entries = (room: RoomState, online: ReadonlySet<string>): DeviceEntry[] =>
  Object.values(room.devices).map((d) => ({ ...d, online: online.has(d.deviceId) }));

const devicesFrame = (room: RoomState, online: ReadonlySet<string>, serverMs: number): ServerMessage => ({
  type: 'devices',
  serverMs,
  devices: entries(room, online),
  activeDeviceId: room.activeDeviceId,
});

/** A device connected and said hello (its socket is already in [online]). */
export function onHello(room: RoomState, msg: Extract<ClientMessage, { type: 'hello' }>, online: ReadonlySet<string>, now: number): Step {
  const id = msg.device.deviceId;
  if (msg.v !== CONNECT_PROTOCOL) return { room, out: [{ to: id, message: { type: 'error', code: 'protocol' } }], reject: id };
  if (!room.devices[id] && Object.keys(room.devices).length >= MAX_DEVICES) {
    // The room is full of devices that are not online: forget the stale ones first.
    const stale = Object.keys(room.devices).filter((d) => !online.has(d) && d !== room.activeDeviceId);
    if (stale.length === 0) return { room, out: [{ to: id, message: { type: 'error', code: 'too_many_devices' } }], reject: id };
    const devices = { ...room.devices };
    delete devices[stale[0]!];
    room = { ...room, devices };
  }
  const next: RoomState = {
    ...room,
    devices: { ...room.devices, [id]: msg.device },
    activeLostAtMs: room.activeDeviceId === id ? null : room.activeLostAtMs,
  };
  return {
    room: next,
    out: [
      {
        to: id,
        message: {
          type: 'welcome',
          serverMs: now,
          devices: entries(next, online),
          activeDeviceId: next.activeDeviceId,
          state: next.state,
          stateServerMs: next.stateServerMs,
        },
      },
      { all: true, except: id, message: devicesFrame(next, online, now) },
    ],
  };
}

/** A device's socket closed. */
export function onClose(room: RoomState, deviceId: string, online: ReadonlySet<string>, now: number): Step {
  const lost = room.activeDeviceId === deviceId && room.activeLostAtMs === null;
  const next = lost ? { ...room, activeLostAtMs: now } : room;
  return { room: next, out: [{ all: true, message: devicesFrame(next, online, now) }] };
}

/** The time check: the active device that stayed offline past the grace period is let go. */
export function onTick(room: RoomState, online: ReadonlySet<string>, now: number): Step {
  if (room.activeDeviceId === null || room.activeLostAtMs === null || now - room.activeLostAtMs < ACTIVE_GRACE_MS) return { room, out: [] };
  if (online.has(room.activeDeviceId)) return { room: { ...room, activeLostAtMs: null }, out: [] };
  const state = room.state ? { ...room.state, isPlaying: false } : null;
  const next: RoomState = { ...room, activeDeviceId: null, activeLostAtMs: null, state };
  const out: Outbound[] = [{ all: true, message: devicesFrame(next, online, now) }];
  if (state) out.push({ all: true, message: { type: 'state', serverMs: now, from: '', state } });
  return { room: next, out };
}

/** A frame from [from], an online device that has said hello. */
export function onMessage(room: RoomState, from: string, msg: ClientMessage, online: ReadonlySet<string>, now: number): Step {
  switch (msg.type) {
    case 'hello':
      return { room, out: [] };
    case 'ping':
      return { room, out: [{ to: from, message: { type: 'pong', clientMs: msg.clientMs, serverMs: now } }] };
    case 'state': {
      // Only the active device speaks for the session; a stale one is told it is a remote now.
      if (from !== room.activeDeviceId) return { room, out: [{ to: from, message: { type: 'release', serverMs: now, to: room.activeDeviceId } }] };
      const next = { ...room, state: msg.state, stateServerMs: now, activeLostAtMs: null };
      return { room: next, out: [{ all: true, except: from, message: { type: 'state', serverMs: now, from, state: msg.state } }] };
    }
    case 'claim': {
      const previous = room.activeDeviceId;
      const next: RoomState = { ...room, activeDeviceId: from, state: msg.state, stateServerMs: now, activeLostAtMs: null };
      const out: Outbound[] = [];
      if (previous && previous !== from && online.has(previous)) out.push({ to: previous, message: { type: 'release', serverMs: now, to: from } });
      out.push({ all: true, message: devicesFrame(next, online, now) });
      out.push({ all: true, except: from, message: { type: 'state', serverMs: now, from, state: msg.state } });
      return { room: next, out };
    }
    case 'transfer': {
      const target = room.devices[msg.to];
      if (!target || !online.has(msg.to)) return { room, out: [{ to: from, message: { type: 'error', code: 'unknown_device' } }] };
      if (msg.to === room.activeDeviceId) return { room, out: [] };
      const previous = room.activeDeviceId;
      // The state the target resumes from, stopped at the moment of the hand-over if it was running.
      const state = room.state;
      const resume = state ? { ...state, positionMs: positionAt(state, room.stateServerMs, now) } : null;
      const next: RoomState = { ...room, activeDeviceId: msg.to, state: resume, stateServerMs: now, activeLostAtMs: null };
      const out: Outbound[] = [{ to: msg.to, message: { type: 'takeover', serverMs: now, state: resume, play: msg.play } }];
      if (previous && previous !== msg.to && online.has(previous)) out.push({ to: previous, message: { type: 'release', serverMs: now, to: msg.to } });
      out.push({ all: true, except: msg.to, message: devicesFrame(next, online, now) });
      return { room: next, out };
    }
    case 'command': {
      const target = msg.to ?? room.activeDeviceId;
      if (!target) return { room, out: [{ to: from, message: { type: 'error', code: 'no_active' } }] };
      if (!room.devices[target] || !online.has(target)) return { room, out: [{ to: from, message: { type: 'error', code: 'unknown_device' } }] };
      return { room, out: [{ to: target, message: { type: 'command', serverMs: now, from, command: msg.command } }] };
    }
  }
}

/** Where the song is at [now], given the state sent at [stateServerMs]. */
export function positionAt(state: PlaybackState, stateServerMs: number, now: number): number {
  return state.positionMs + (state.isPlaying ? Math.max(0, now - stateServerMs) : 0);
}
