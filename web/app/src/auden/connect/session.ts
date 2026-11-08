import {
  STATE_HEARTBEAT_MS,
  type ClientMessage,
  type Command,
  type ConnectPlatform,
  type ConnectTrack,
  type PlaybackState,
  type ServerMessage,
} from '@bitchord/shared/connect';
import { config } from '@/config';
import type { Track } from '@/data/music/types';
import { connectRoomId, deviceId, useParty } from '@/party/listenTogether';
import { isShareable } from '@/playback/source';
import { isRestoringLastSession } from '@/state/lastSession';
import {
  adoptShuffleOrder,
  canonicalOrderOf,
  getPlayhead,
  isMirrored,
  mirrorPlayer,
  partyPlayer,
  setMirrorHooks,
  usePlayer,
  type MirrorHooks,
  type MirrorView,
} from '@/state/player';
import { ConnectClient } from './client';
import { applyOptimistic } from './optimistic';
import { remotePlayheadSec, useConnect } from './store';

/**
 * Auden Connect, the glue: one signed-in account's devices handing playback to each other (web/connect, the
 * protocol in shared/src/connect). This browser is either the ACTIVE device (it makes the sound and publishes
 * what it plays) or a MIRROR of another one: the normal player (bar, full screen, Up Next, lyrics) shows the
 * same session as if it were playing it, with no sound here, and every control is a command to the device that
 * plays. Only the output changes when playback moves.
 *
 * A mirrored player is flagged (`mirror` in the player store): history, Discord, the listening stats, Automix,
 * AutoPlay and Listen Together all follow that store, and none of them acts on a song that plays on a phone.
 */

let client: ConnectClient | null = null;
/** The ids of the entries last published, by published index: a remote's "jump" names one. */
let published: string[] = [];
let heartbeat: ReturnType<typeof setInterval> | null = null;
let debounce: ReturnType<typeof setTimeout> | null = null;
let applying = false;
/** A release hands the player to the mirror before its own audio has finished pausing. */
let forcing = false;

const iAmActive = () => {
  const s = useConnect.getState();
  return s.myId !== '' && s.activeId === s.myId;
};

function platform(): ConnectPlatform {
  return 'web';
}

function deviceName(): string {
  const ua = navigator.userAgent;
  const browser = /Edg\//.test(ua) ? 'Edge' : /OPR\//.test(ua) ? 'Opera' : /Firefox\//.test(ua) ? 'Firefox' : /Chrome\//.test(ua) ? 'Chrome' : /Safari\//.test(ua) ? 'Safari' : 'Browser';
  const os = /Android/.test(ua) ? 'Android' : /iPhone|iPad/.test(ua) ? 'iOS' : /Windows/.test(ua) ? 'Windows' : /Mac OS/.test(ua) ? 'Mac' : /Linux/.test(ua) ? 'Linux' : '';
  return os ? `${browser} on ${os}` : browser;
}

// ---- The player, as a PlaybackState ---------------------------------------------------------

const toConnectTrack = (t: Track): ConnectTrack => ({
  videoId: t.videoId,
  title: t.title,
  artist: t.artists.map((a) => a.name).join(', '),
  ...(t.album?.name ? { album: t.album.name } : {}),
  ...(t.thumbnail ? { artworkUrl: t.thumbnail } : {}),
  ...(t.durationSec ? { durationSec: t.durationSec } : {}),
});

const toTrack = (c: ConnectTrack): Track => ({
  videoId: c.videoId,
  title: c.title,
  artists: c.artist ? [{ name: c.artist, id: null }] : [],
  album: c.album ? { name: c.album, id: null } : null,
  durationSec: c.durationSec ?? null,
  explicit: false,
  thumbnail: c.artworkUrl ?? null,
  index: null,
  plays: null,
});

/** The most of a long queue that is carried: some of what played, all of what is next, up to the protocol's limit. */
const BEHIND = 10;
const AHEAD = 89;

/** What this browser is playing, as the protocol states it; null when there is nothing to share. */
export function localState(): PlaybackState | null {
  const p = usePlayer.getState();
  const current = p.queue[p.index];
  if (!p.nowPlaying || !current || !isShareable(current.track)) return null;
  const from = Math.max(0, p.index - BEHIND);
  const slice = p.queue.slice(from, p.index + AHEAD + 1).filter((e) => isShareable(e.track));
  const index = slice.findIndex((e) => e.entryId === current.entryId);
  if (index < 0) return null;
  published = slice.map((e) => e.entryId);
  const head = getPlayhead();
  return {
    queue: slice.map((e) => toConnectTrack(e.track)),
    index,
    positionMs: Math.max(0, Math.round((head?.sec ?? 0) * 1000)),
    isPlaying: p.status === 'playing' || p.status === 'buffering' || p.status === 'loading',
    volume: p.muted ? 0 : p.volume,
    shuffle: p.shuffle,
    repeat: p.repeat,
    // Shuffle on: the list as it was before, so any device can turn shuffle off and land on the same one.
    ...(p.shuffle ? { order: canonicalOrderOf(slice).map((e) => e.track.videoId) } : {}),
  };
}

const IDLE: PlaybackState = { queue: [], index: -1, positionMs: 0, isPlaying: false, volume: 1, shuffle: false, repeat: 'off' };

function send(message: ClientMessage): boolean {
  return client?.send(message) ?? false;
}

/** The active device says where it is. */
function publish(): void {
  if (!iAmActive() || applying) return;
  send({ type: 'state', state: localState() ?? { ...IDLE, volume: usePlayer.getState().volume } });
}

function publishSoon(): void {
  if (debounce) clearTimeout(debounce);
  debounce = setTimeout(publish, 120);
}

// ---- Becoming active, and giving it up ------------------------------------------------------

function claimIfPlaying(): void {
  const { activeId, myId, status } = useConnect.getState();
  // A mirror is not playing anything: its state changes are the other device's, never a reason to take the session.
  if (status !== 'online' || activeId === myId || applying || isMirrored()) return;
  const p = usePlayer.getState();
  // A party's queue is the party's, not a session to carry to another device.
  if (p.queue[p.index]?.source?.title === 'Listen Together') return;
  const state = localState();
  // Sounding, not merely loading: the last session being put back after a reload loads without anyone asking for sound.
  if (!state?.isPlaying || (p.status !== 'playing' && p.status !== 'buffering')) return;
  useConnect.setState({ activeId: myId, remote: null });
  send({ type: 'claim', state });
}

function takeover(state: PlaybackState | null, play: boolean): void {
  const { myId } = useConnect.getState();
  const loads = !!state && state.index >= 0 && state.queue.length > 0;
  // Leaving the mirror and loading the same session is one move: nothing in between is the user's.
  if (loads) applying = true;
  mirrorPlayer.leave();
  useConnect.setState({ activeId: myId, remote: null });
  if (!loads || !state) return;
  // Volume stays this browser's own: the state's is the device that played it.
  usePlayer.setState({ shuffle: state.shuffle, repeat: state.repeat });
  void partyPlayer
    .load(state.queue.map(toTrack), state.queue.map(() => 'context' as const), state.index, state.positionMs / 1000, play, null)
    .finally(() => {
      applying = false;
      publish();
    });
  // The queue is on the player now: shuffle's order comes with it, so turning shuffle off lands on the same list.
  if (state.shuffle && state.order) adoptShuffleOrder(state.order);
}

function release(to: string | null): void {
  const mine = localState();
  partyPlayer.pause();
  const { offsetMs } = useConnect.getState();
  // What this browser was playing stays on its screen, now as a mirror of the device that took over.
  forcing = true;
  try {
    useConnect.setState({
      activeId: to,
      remote: mine ? { state: mine, atServerMs: Date.now() + offsetMs } : null,
    });
  } finally {
    forcing = false;
  }
}

// ---- The mirror ---------------------------------------------------------------------------

const viewOf = (state: PlaybackState): MirrorView => ({
  tracks: state.queue.map(toTrack),
  index: state.index,
  playing: state.isPlaying,
  shuffle: state.shuffle,
  repeat: state.repeat,
  volume: state.volume,
});

function mirrorWanted(): boolean {
  const s = useConnect.getState();
  const state = s.remote?.state;
  return !!state && s.myId !== '' && s.activeId !== null && s.activeId !== s.myId && !!state.queue[state.index] && !useParty.getState().code;
}

/** The player shows what the remote is playing while another device is the active one, and goes back to itself when none is. */
function syncMirror(): void {
  const remote = useConnect.getState().remote;
  if (remote && mirrorWanted()) {
    // Sound of its own that nobody has claimed (a local file, a party) keeps the floor; the claim follows it.
    const status = usePlayer.getState().status;
    if (!isMirrored() && !forcing && !isRestoringLastSession() && (status === 'playing' || status === 'buffering' || status === 'loading')) return;
    mirrorPlayer.show(viewOf(remote.state));
  } else if (isMirrored()) {
    mirrorPlayer.leave();
  }
}

/** The listener starts something new in this browser while it mirrors: the other device is released and this one plays. */
function leaveForNewContext(): void {
  const { status, myId } = useConnect.getState();
  mirrorPlayer.leave(true);
  if (status !== 'online') {
    useConnect.setState({ remote: null });
    return;
  }
  useConnect.setState({ activeId: myId, remote: null });
  // Nothing to carry yet (the new context is loading, or is a file only this browser has); its state follows.
  send({ type: 'claim', state: { ...IDLE, volume: usePlayer.getState().volume } });
}

const shareable = (tracks: Track[]): ConnectTrack[] => tracks.filter(isShareable).map(toConnectTrack);
const clampVolume = (v: number) => Math.min(1, Math.max(0, v));
/** The volume before a mute from here, for the unmute. */
let unmuteTo = 0.5;

const mirrorHooks: MirrorHooks = {
  togglePlay: () => void remoteCommand({ action: 'toggle' }),
  next: () => void remoteCommand({ action: 'next' }),
  previous: () => void remoteCommand({ action: 'previous' }),
  seek: (seconds) => void remoteCommand({ action: 'seek', value: Math.max(0, Math.round(seconds * 1000)) }),
  toggleShuffle: () => void remoteCommand({ action: 'shuffle', value: !useConnect.getState().remote?.state.shuffle }),
  cycleRepeat: () => {
    const repeat = useConnect.getState().remote?.state.repeat ?? 'off';
    void remoteCommand({ action: 'repeat', value: { off: 'all', all: 'one', one: 'off' }[repeat] as 'off' | 'all' | 'one' });
  },
  setVolume: (volume) => void remoteCommand({ action: 'volume', value: clampVolume(volume) }),
  toggleMute: () => {
    const volume = useConnect.getState().remote?.state.volume ?? 0;
    if (volume > 0) unmuteTo = volume;
    void remoteCommand({ action: 'volume', value: volume > 0 ? 0 : unmuteTo });
  },
  jumpTo: (index) => void remoteCommand({ action: 'jump', value: index }),
  playNext: (tracks) => {
    const list = shareable(tracks);
    if (list.length) void remoteCommand({ action: 'playNext', tracks: list });
  },
  addToQueue: (tracks) => {
    const list = shareable(tracks);
    if (list.length) void remoteCommand({ action: 'addToQueue', tracks: list });
  },
  removeAt: (index) => void remoteCommand({ action: 'remove', value: index }),
  move: (from, to) => void remoteCommand({ action: 'move', value: from, to }),
  clearUpNext: () => void remoteCommand({ action: 'clearUpcoming' }),
  leave: leaveForNewContext,
  playhead: () => {
    const remote = useConnect.getState().remote;
    return remote ? { sec: remotePlayheadSec(), paused: !remote.state.isPlaying } : null;
  },
};

// ---- Commands from a remote -----------------------------------------------------------------

/** The queue index of the entry that was at [publishedIndex] when the remote last saw the queue; -1 when it has gone. */
function entryAt(publishedIndex: unknown): number {
  const id = typeof publishedIndex === 'number' ? published[publishedIndex] : undefined;
  return id ? usePlayer.getState().queue.findIndex((e) => e.entryId === id) : -1;
}

function applyCommand(command: Command): void {
  const p = usePlayer.getState();
  switch (command.action) {
    case 'play':
      partyPlayer.play();
      break;
    case 'pause':
      partyPlayer.pause();
      break;
    case 'toggle':
      p.togglePlay();
      break;
    case 'next':
      p.next();
      break;
    case 'previous':
      p.previous();
      break;
    case 'seek':
      if (typeof command.value === 'number') partyPlayer.seekTo(command.value / 1000);
      break;
    case 'volume':
      if (typeof command.value === 'number') p.setVolume(command.value);
      break;
    case 'shuffle':
      // The web's own shuffle: it reorders the queue and keeps the order to come back to.
      if (typeof command.value === 'boolean' && command.value !== p.shuffle) p.toggleShuffle();
      break;
    case 'repeat':
      for (let i = 0; i < 3 && (command.value === 'off' || command.value === 'all' || command.value === 'one') && usePlayer.getState().repeat !== command.value; i++) {
        usePlayer.getState().cycleRepeat();
      }
      break;
    case 'jump': {
      const target = entryAt(command.value);
      if (target >= 0) p.jumpTo(target);
      break;
    }
    case 'playNext':
    case 'addToQueue': {
      const tracks = (command.tracks ?? []).map(toTrack);
      if (tracks.length) (command.action === 'playNext' ? p.playNext : p.addToQueue)(tracks);
      break;
    }
    case 'remove': {
      const at = entryAt(command.value);
      if (at >= 0) p.removeAt(at);
      break;
    }
    case 'move': {
      const from = entryAt(command.value);
      const to = entryAt(command.to);
      if (from >= 0 && to >= 0) p.move(from, to);
      break;
    }
    case 'clearUpcoming':
      // Everything after the playing song, whoever queued it: what a remote's Clear means.
      partyPlayer.setUpcoming([], []);
      break;
  }
  publishSoon();
}

// ---- Messages -------------------------------------------------------------------------------

function onMessage(message: ServerMessage): void {
  switch (message.type) {
    case 'welcome': {
      const myId = useConnect.getState().myId;
      useConnect.setState({
        devices: message.devices,
        activeId: message.activeDeviceId,
        offsetMs: message.serverMs - Date.now(),
        remote: message.state && message.activeDeviceId && message.activeDeviceId !== myId ? { state: message.state, atServerMs: message.stateServerMs } : null,
      });
      // A reload leaves the room thinking this browser still plays: say what it really holds.
      if (message.activeDeviceId === myId) publish();
      else claimIfPlaying();
      break;
    }
    case 'devices':
      useConnect.setState((s) => ({
        devices: message.devices,
        activeId: message.activeDeviceId,
        remote: message.activeDeviceId === null || message.activeDeviceId === s.myId ? null : s.remote,
      }));
      break;
    case 'state':
      if (!message.from || message.from !== useConnect.getState().myId) {
        useConnect.setState((s) => ({ remote: s.activeId === s.myId ? null : { state: message.state, atServerMs: message.serverMs } }));
      }
      break;
    case 'command':
      if (iAmActive()) applyCommand(message.command);
      break;
    case 'takeover':
      takeover(message.state, message.play);
      break;
    case 'release':
      if (iAmActive() || useConnect.getState().activeId === null) release(message.to);
      break;
    case 'error':
    case 'pong':
      break;
  }
}

// ---- What the user does ---------------------------------------------------------------------

/** Plays on [targetId]: this browser or another device. */
export function switchTo(targetId: string): void {
  const s = useConnect.getState();
  if (targetId === s.activeId) return;
  if (targetId === s.myId) {
    send({ type: 'transfer', to: s.myId, play: s.remote?.state.isPlaying ?? true });
    return;
  }
  const mine = iAmActive() ? localState() : null;
  if (iAmActive() && !mine) return;
  // The room hands over the last state it holds: make it this moment's.
  if (mine) send({ type: 'state', state: mine });
  send({ type: 'transfer', to: targetId, play: mine ? mine.isPlaying : (s.remote?.state.isPlaying ?? true) });
}

/**
 * A control for the device that is playing (the mirror's buttons); false when there is none to drive. The
 * screen answers at once with what the command should do (optimistic.ts) and the device's next state is the truth.
 */
export function remoteCommand(command: Command): boolean {
  const s = useConnect.getState();
  if (!s.remote || s.activeId === s.myId) return false;
  const sent = send({ type: 'command', command });
  if (sent) useConnect.setState({ remote: applyOptimistic(s.remote, command, Date.now() + s.offsetMs) });
  return sent;
}

// ---- Lifecycle ------------------------------------------------------------------------------

/** Joins the account's room until the returned function is called. A no-op without a server or a signed-in account. */
export async function startConnect(identity: { room?: string; deviceId?: string; name?: string } = {}): Promise<() => void> {
  if (!config.connectUrl) return () => {};
  const room = identity.room ?? (await connectRoomId());
  if (!room) return () => {};
  const myId = identity.deviceId ?? deviceId();
  useConnect.setState({ myId, devices: [], activeId: null, remote: null });
  const c = new ConnectClient({
    url: config.connectUrl,
    room,
    device: { deviceId: myId, name: identity.name ?? deviceName(), platform: platform() },
    onMessage,
    onStatus: (status) => useConnect.setState({ status }),
  });
  client = c;
  c.start();

  setMirrorHooks(mirrorHooks);
  const offConnect = useConnect.subscribe((s, before) => {
    if (s.remote !== before.remote || s.activeId !== before.activeId || s.myId !== before.myId) syncMirror();
  });
  const offPlayer = usePlayer.subscribe((s, before) => {
    if (iAmActive()) {
      if (
        s.nowPlaying?.videoId !== before.nowPlaying?.videoId ||
        s.status !== before.status ||
        s.index !== before.index ||
        s.queue !== before.queue ||
        s.shuffle !== before.shuffle ||
        s.repeat !== before.repeat ||
        s.volume !== before.volume ||
        s.muted !== before.muted
      ) {
        publishSoon();
      }
    } else if (s.status !== before.status || s.nowPlaying?.videoId !== before.nowPlaying?.videoId) {
      claimIfPlaying();
    }
  });
  const onSeeked = () => publishSoon();
  document.addEventListener('seeked', onSeeked, true);
  heartbeat = setInterval(() => {
    if (iAmActive() && usePlayer.getState().status === 'playing') publish();
  }, STATE_HEARTBEAT_MS);

  return () => {
    offConnect();
    setMirrorHooks(null);
    // The session shown here was another device's: with Connect gone it goes too.
    if (isMirrored()) mirrorPlayer.leave(true);
    offPlayer();
    document.removeEventListener('seeked', onSeeked, true);
    if (heartbeat) clearInterval(heartbeat);
    if (debounce) clearTimeout(debounce);
    heartbeat = null;
    c.stop();
    if (client === c) client = null;
    useConnect.setState({ status: 'off', devices: [], activeId: null, remote: null });
  };
}

// For browser-driven checks (a second device is a script): joins a given room under a given identity, next to __bcPlayer.
if (import.meta.env.DEV && typeof window !== 'undefined') {
  Object.assign(window, {
    __bcConnect: {
      start: (room: string, id = 'web-dev', name = 'Dev browser') => startConnect({ room, deviceId: id, name }),
      useConnect,
    },
  });
}
