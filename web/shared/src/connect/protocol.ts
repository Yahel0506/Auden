/**
 * Auden Connect: one signed-in account's own devices handing playback to each other, as Spotify Connect does.
 *
 * Every device of the account opens a WebSocket to the Connect server, into a room named by the account's id
 * hash (the same `sha256(account:profile).slice(0, 32)` Listen Together calls userId). The room knows who is
 * online, which one is the ACTIVE device (the one making sound) and the last playback state it published.
 * Everyone else is a remote: it shows that state and sends commands to the active device.
 *
 * The phone speaks the same JSON (camelCase), so every field here is plain and optional ones may be absent.
 * Nothing in the server verifies the account (it holds no Google credential, like the party server): the room
 * id is an unguessable hash, and that is the whole access control. See web/connect/README.md.
 */

export const CONNECT_PROTOCOL = 1;

export type ConnectPlatform = 'web' | 'android' | 'tv' | 'watch' | 'pc';
export const PLATFORMS: readonly ConnectPlatform[] = ['web', 'android', 'tv', 'watch', 'pc'];

export interface ConnectDevice {
  deviceId: string;
  name: string;
  platform: ConnectPlatform;
}

/** A song in the shared order: enough for any device to show it and to load it from YouTube. */
export interface ConnectTrack {
  videoId: string;
  title: string;
  artist: string;
  album?: string;
  artworkUrl?: string;
  durationSec?: number;
}

export type ConnectRepeat = 'off' | 'all' | 'one';

export interface PlaybackState {
  /** The whole running order (the playing song included), at most MAX_QUEUE long. */
  queue: ConnectTrack[];
  /** Position of the playing song in [queue]; -1 when nothing is loaded. */
  index: number;
  /** Where the song was, in ms, at the moment the active device sent this. */
  positionMs: number;
  isPlaying: boolean;
  /** 0..1, the active device's own volume. */
  volume: number;
  shuffle: boolean;
  repeat: ConnectRepeat;
  /**
   * While shuffle is on: the queue's own order from before the shuffle, as videoIds, so any device can turn
   * shuffle off and land on the same list. Absent when shuffle is off.
   */
  order?: string[];
}

export type CommandAction =
  | 'play' | 'pause' | 'toggle' | 'next' | 'previous' | 'seek' | 'volume' | 'shuffle' | 'repeat' | 'jump'
  // Queue edits: the active device applies them and publishes the new queue, so every device shows the same one.
  | 'playNext' | 'addToQueue' | 'remove' | 'move' | 'clearUpcoming';

export interface Command {
  action: CommandAction;
  /**
   * seek: ms; volume: 0..1; jump / remove: queue index; move: the index to take; shuffle: boolean;
   * repeat: 'off' | 'all' | 'one'.
   */
  value?: number | boolean | ConnectRepeat;
  /** move: where the song goes. */
  to?: number;
  /** playNext / addToQueue: the songs, in order. */
  tracks?: ConnectTrack[];
}

// ---- Client -> server --------------------------------------------------------------------

export type ClientMessage =
  | { type: 'hello'; v: number; device: ConnectDevice }
  /** The active device's playback state; sent on every change and every few seconds while playing. */
  | { type: 'state'; state: PlaybackState }
  /** "I am starting playback here": this device becomes the active one and the previous one is released. */
  | { type: 'claim'; state: PlaybackState }
  /** Hand playback to [to]; [play] says whether it should be sounding when it lands. */
  | { type: 'transfer'; to: string; play: boolean }
  /** A remote control for the active device (or for [to]). */
  | { type: 'command'; to?: string; command: Command }
  | { type: 'ping'; clientMs: number };

// ---- Server -> client --------------------------------------------------------------------

export interface DeviceEntry extends ConnectDevice {
  /** True when its socket is open right now. */
  online: boolean;
}

export type ServerMessage =
  | { type: 'welcome'; serverMs: number; devices: DeviceEntry[]; activeDeviceId: string | null; state: PlaybackState | null; stateServerMs: number }
  | { type: 'devices'; serverMs: number; devices: DeviceEntry[]; activeDeviceId: string | null }
  | { type: 'state'; serverMs: number; from: string; state: PlaybackState }
  | { type: 'command'; serverMs: number; from: string; command: Command }
  /** You are the active device now: load [state] and, when [play], sound. State is null for a bare takeover. */
  | { type: 'takeover'; serverMs: number; state: PlaybackState | null; play: boolean }
  /** You are no longer the active device: stop, and show [to]'s playback as a remote. */
  | { type: 'release'; serverMs: number; to: string | null }
  | { type: 'pong'; clientMs: number; serverMs: number }
  | { type: 'error'; code: ConnectErrorCode };

export type ConnectErrorCode = 'bad_message' | 'no_active' | 'unknown_device' | 'not_hello' | 'too_many_devices' | 'protocol';

// ---- Limits (the server enforces them; clients trim to them) -----------------------------

export const MAX_MESSAGE_BYTES = 96 * 1024;
export const MAX_QUEUE = 100;
export const MAX_DEVICES = 8;
export const MAX_TEXT = 300;
/** How long the active device may be offline before the session is let go. */
export const ACTIVE_GRACE_MS = 20_000;
/** The active device re-states its position this often while playing. */
export const STATE_HEARTBEAT_MS = 3_000;

// ---- Validation --------------------------------------------------------------------------

const isObject = (v: unknown): v is Record<string, unknown> => typeof v === 'object' && v !== null && !Array.isArray(v);
const isText = (v: unknown, max = MAX_TEXT): v is string => typeof v === 'string' && v.length <= max;
const isFiniteNumber = (v: unknown): v is number => typeof v === 'number' && Number.isFinite(v);

function parseTrack(raw: unknown): ConnectTrack | null {
  if (!isObject(raw)) return null;
  if (!isText(raw.videoId, 64) || !raw.videoId || !isText(raw.title) || !isText(raw.artist)) return null;
  const track: ConnectTrack = { videoId: raw.videoId, title: raw.title, artist: raw.artist };
  if (raw.album !== undefined && raw.album !== null) {
    if (!isText(raw.album)) return null;
    track.album = raw.album;
  }
  if (raw.artworkUrl !== undefined && raw.artworkUrl !== null) {
    if (!isText(raw.artworkUrl, 600)) return null;
    if (raw.artworkUrl.startsWith('http')) track.artworkUrl = raw.artworkUrl;
  }
  if (raw.durationSec !== undefined && raw.durationSec !== null) {
    if (!isFiniteNumber(raw.durationSec) || raw.durationSec < 0) return null;
    track.durationSec = Math.round(raw.durationSec);
  }
  return track;
}

export function parsePlaybackState(raw: unknown): PlaybackState | null {
  if (!isObject(raw) || !Array.isArray(raw.queue) || raw.queue.length > MAX_QUEUE) return null;
  const queue: ConnectTrack[] = [];
  for (const item of raw.queue) {
    const track = parseTrack(item);
    if (!track) return null;
    queue.push(track);
  }
  const index = raw.index;
  if (!Number.isInteger(index) || (index as number) < -1 || (index as number) >= Math.max(queue.length, 1)) return null;
  if (!isFiniteNumber(raw.positionMs) || raw.positionMs < 0) return null;
  if (typeof raw.isPlaying !== 'boolean' || typeof raw.shuffle !== 'boolean') return null;
  if (!isFiniteNumber(raw.volume)) return null;
  if (raw.repeat !== 'off' && raw.repeat !== 'all' && raw.repeat !== 'one') return null;
  let order: string[] | undefined;
  if (raw.order !== undefined && raw.order !== null) {
    if (!Array.isArray(raw.order) || raw.order.length > MAX_QUEUE || !raw.order.every((id) => isText(id, 64))) return null;
    if (raw.shuffle) order = raw.order as string[];
  }
  return {
    queue,
    index: index as number,
    positionMs: Math.round(raw.positionMs),
    isPlaying: raw.isPlaying,
    volume: Math.min(1, Math.max(0, raw.volume)),
    shuffle: raw.shuffle,
    repeat: raw.repeat,
    ...(order ? { order } : {}),
  };
}

const ACTIONS: readonly CommandAction[] = [
  'play', 'pause', 'toggle', 'next', 'previous', 'seek', 'volume', 'shuffle', 'repeat', 'jump',
  'playNext', 'addToQueue', 'remove', 'move', 'clearUpcoming',
];

function parseCommand(raw: unknown): Command | null {
  if (!isObject(raw) || typeof raw.action !== 'string' || !ACTIONS.includes(raw.action as CommandAction)) return null;
  const action = raw.action as CommandAction;
  const value = raw.value;
  switch (action) {
    case 'seek':
    case 'jump':
    case 'remove':
      return isFiniteNumber(value) && value >= 0 ? { action, value: Math.round(value) } : null;
    case 'move':
      return isFiniteNumber(value) && value >= 0 && isFiniteNumber(raw.to) && raw.to >= 0 ? { action, value: Math.round(value), to: Math.round(raw.to) } : null;
    case 'playNext':
    case 'addToQueue': {
      if (!Array.isArray(raw.tracks) || raw.tracks.length === 0 || raw.tracks.length > MAX_QUEUE) return null;
      const tracks: ConnectTrack[] = [];
      for (const item of raw.tracks) {
        const track = parseTrack(item);
        if (!track) return null;
        tracks.push(track);
      }
      return { action, tracks };
    }
    case 'volume':
      return isFiniteNumber(value) ? { action, value: Math.min(1, Math.max(0, value)) } : null;
    case 'shuffle':
      return typeof value === 'boolean' ? { action, value } : null;
    case 'repeat':
      return value === 'off' || value === 'all' || value === 'one' ? { action, value } : null;
    default:
      return { action };
  }
}

function parseDevice(raw: unknown): ConnectDevice | null {
  if (!isObject(raw)) return null;
  if (!isText(raw.deviceId, 64) || !raw.deviceId || !isText(raw.name, 80)) return null;
  if (typeof raw.platform !== 'string' || !PLATFORMS.includes(raw.platform as ConnectPlatform)) return null;
  return { deviceId: raw.deviceId, name: raw.name.trim() || 'Device', platform: raw.platform as ConnectPlatform };
}

/** One frame from a device, or null when it is not a well-formed, in-limits message of ours. */
export function parseClientMessage(text: string): ClientMessage | null {
  if (text.length > MAX_MESSAGE_BYTES) return null;
  let raw: unknown;
  try {
    raw = JSON.parse(text);
  } catch {
    return null;
  }
  if (!isObject(raw) || typeof raw.type !== 'string') return null;
  switch (raw.type) {
    case 'hello': {
      const device = parseDevice(raw.device);
      return device && isFiniteNumber(raw.v) ? { type: 'hello', v: raw.v, device } : null;
    }
    case 'state':
    case 'claim': {
      const state = parsePlaybackState(raw.state);
      return state ? { type: raw.type, state } : null;
    }
    case 'transfer':
      return isText(raw.to, 64) && raw.to && typeof raw.play === 'boolean' ? { type: 'transfer', to: raw.to, play: raw.play } : null;
    case 'command': {
      const command = parseCommand(raw.command);
      if (!command) return null;
      if (raw.to !== undefined && !(isText(raw.to, 64) && raw.to)) return null;
      return raw.to === undefined ? { type: 'command', command } : { type: 'command', to: raw.to as string, command };
    }
    case 'ping':
      return isFiniteNumber(raw.clientMs) ? { type: 'ping', clientMs: raw.clientMs } : null;
    default:
      return null;
  }
}

/** Where the song is now, in ms: the state's position plus what has passed since it was true. */
export function playheadMs(state: PlaybackState, stateServerMs: number, serverNowMs: number, durationMs?: number): number {
  const elapsed = state.isPlaying ? Math.max(0, serverNowMs - stateServerMs) : 0;
  const at = state.positionMs + elapsed;
  return durationMs && durationMs > 0 ? Math.min(at, durationMs) : at;
}
