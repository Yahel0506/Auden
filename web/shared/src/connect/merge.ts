import type { DeviceEntry, ServerMessage } from './protocol';

/** How long a second `takeover` / `release` of the same kind, from another room, is taken for the first one again. */
const DUPLICATE_MS = 1_000;

interface RoomView {
  devices: DeviceEntry[];
  active: string | null;
}

/**
 * Several rooms as one. A device with no Google account can be in more than one room at once — its Spotify
 * account's and the one for its network — and each is a room of its own on the server, with its own device list and
 * its own idea of who is playing. The device's session takes one stream of messages, so this folds them into it:
 *
 * - the device list is every room's, each device once;
 * - the active device is this device when any room says so, else the one of the room whose state arrived first,
 *   else the first room that has one;
 * - a state is passed on only from a room whose active device is the one chosen;
 * - the first `welcome` is the session's welcome, the next ones refresh the list (and the state, when it is the
 *   chosen one's); a `takeover` or `release` that arrives from two rooms at once is passed on once.
 *
 * Everything else is passed on untouched. What is sent goes to every room (see the callers): a room that does not
 * hold the target ignores it.
 */
export class RoomMerger {
  private readonly rooms = new Map<number, RoomView>();
  private welcomed = false;
  private stateRoom: number | null = null;
  private lastOnce = new Map<string, number>();

  constructor(private readonly myId: string) {}

  /** What to deliver to the session for [message] from room number [room]. */
  feed(room: number, message: ServerMessage, nowMs: number = Date.now()): ServerMessage[] {
    switch (message.type) {
      case 'welcome': {
        this.rooms.set(room, { devices: message.devices, active: message.activeDeviceId });
        const active = this.active();
        if (!this.welcomed) {
          this.welcomed = true;
          const mine = message.activeDeviceId === active;
          return [{ ...message, devices: this.devices(), activeDeviceId: active, state: mine ? message.state : null }];
        }
        const out: ServerMessage[] = [this.devicesMessage(message.serverMs)];
        if (message.state && message.activeDeviceId && message.activeDeviceId === active && active !== this.myId) {
          out.push({ type: 'state', serverMs: message.stateServerMs, from: active, state: message.state });
        }
        return out;
      }
      case 'devices': {
        this.rooms.set(room, { devices: message.devices, active: message.activeDeviceId });
        return [this.devicesMessage(message.serverMs)];
      }
      case 'state': {
        if (message.from && message.from === this.myId) return [];
        // The first room to report a state keeps the say until it has no active device of its own.
        if (this.stateRoom === null || !this.rooms.get(this.stateRoom)?.active) this.stateRoom = room;
        const view = this.rooms.get(room);
        const active = this.active();
        // A state from a room whose active device is not the chosen one is the other room's story.
        if (view && active !== null && view.active !== active) return [];
        return [message];
      }
      case 'takeover':
      case 'release': {
        const last = this.lastOnce.get(message.type);
        if (last !== undefined && nowMs - last < DUPLICATE_MS) return [];
        this.lastOnce.set(message.type, nowMs);
        return [message];
      }
      default:
        return [message];
    }
  }

  /** A room's socket went away for good: its devices are no longer listed. Null when no room is left. */
  drop(room: number, serverMs = Date.now()): ServerMessage | null {
    if (!this.rooms.delete(room)) return null;
    if (this.stateRoom === room) this.stateRoom = null;
    return this.rooms.size ? this.devicesMessage(serverMs) : null;
  }

  private devicesMessage(serverMs: number): ServerMessage {
    return { type: 'devices', serverMs, devices: this.devices(), activeDeviceId: this.active() };
  }

  private devices(): DeviceEntry[] {
    const byId = new Map<string, DeviceEntry>();
    for (const view of this.rooms.values()) {
      for (const d of view.devices) {
        const seen = byId.get(d.deviceId);
        byId.set(d.deviceId, seen ? { ...seen, online: seen.online || d.online } : d);
      }
    }
    return [...byId.values()];
  }

  private active(): string | null {
    const views = [...this.rooms.entries()];
    if (views.some(([, v]) => v.active === this.myId)) return this.myId;
    const fromState = this.stateRoom !== null ? this.rooms.get(this.stateRoom)?.active : null;
    if (fromState) return fromState;
    return views.map(([, v]) => v.active).find((a) => a !== null) ?? null;
  }
}
