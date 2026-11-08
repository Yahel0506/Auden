import type { DetectedExtension } from '@bitchord/shared/bridge';
import { create } from 'zustand';
import { djStartQueue, introUnitEnd, removeFromQueue, steerQueue, type DjEntry } from '@/auden/dj/djQueue';
import type { Track } from '@/data/music/types';
import { extensionQueryKey } from '@/extension/useExtension';
import { artworkAtPixels } from '@/lib/artwork';
import { offlineCoverUrl } from '@/auden/offline/kept';
import { queryClient } from '@/lib/queryClient';
import { extendStation } from '@/playback/queue/builder';
import {
  applyJump,
  asEntry,
  autoplaySectionStart,
  BACK_RESTARTS_AFTER_SEC,
  buildContextQueue,
  buildOneOffQueue,
  playedUserQueueIndices,
  sectionedOrder,
  shuffledStartingOrder,
  shuffledUpcomingOrder,
  upcomingUserQueueIndices,
  userQueueInsertionIndex,
  type QueueEntry,
  type QueueSource,
} from '@/playback/queue/timeline';
import { clipBlob, collectClips, heldClipCount } from '@/playback/clips';
import { MediaDeck, type DeckLoadOptions, type DeckMedium } from '@/playback/deck';
import { FileEngine } from '@/playback/fileEngine';
import { forgetExcept, musicOnlyVersion, noteCuts, showsPicture, useMusicOnly, wantsMusicOnly } from '@/playback/musicOnly';
import {
  getLinkProbe,
  getLocalResolver,
  getOfflineProvider,
  offlineCopyPlays,
  mixPolicy,
  isShareable,
  isSyntheticId,
  readMedium,
  resolveMedium,
  sourceOf,
  type Medium,
  type MediumContext,
  type TrackSource,
} from '@/playback/source';
import { isSuperseded, SupersededError } from '@/playback/superseded';
import { trackLog } from '@/playback/trackLog';
import type { LoadedTrack, StreamStats, YouTubeEngine } from '@/playback/youtube/engine';
import type { AutomixGraph, AutoDJSnapshot } from '@/playback/automix';

export interface NowPlaying {
  /** A YouTube videoId, or a synthetic `auden:local:` / `auden:clip:` id (playback/source.ts). */
  videoId: string;
  /** What the track is: only a 'youtube' one goes to YouTube, Discord's links or a party. */
  source: TrackSource;
  /** [source] is YouTube: a party may carry it and YouTube may hear about it. */
  shareable: boolean;
  /** What is playing it: a YouTube song with a linked file plays from 'file'. Null until it loads. */
  medium: 'youtube' | 'file' | null;
  title: string;
  artist: string;
  /** Largest available; size it for display with sizedArtwork(). */
  artworkUrl: string | null;
  durationSec: number;
  /** "251 opus 140kbps via VISIONOS", once known; for the debug panel. */
  streamInfo: string | null;
  /** The audio stream field by field, once known; for stats for nerds. */
  stream: (StreamStats & { client: string }) | null;
}

export type PlayerStatus = 'idle' | 'loading' | 'playing' | 'paused' | 'buffering' | 'error';
export type RepeatMode = 'off' | 'all' | 'one';

/** Crossfade lengths offered, in seconds; 0 is off. */
export const CROSSFADE_CHOICES = [0, 3, 6, 9, 12] as const;

interface Settings {
  shuffle: boolean;
  repeat: RepeatMode;
  /** Keep playing similar music (YouTube Music radio) when the queue runs out. */
  autoplay: boolean;
  volume: number;
  muted: boolean;
  crossfadeSec: number;
  /** AutoMix: beat-matched, phrase-aligned DJ blends between tracks (playback/automix). Overrides crossfadeSec. */
  automix: boolean;
  /** AutoMix's blend length, in bars: a maximum, shortened for weak beat grids. */
  automixBars: number;
  /** Stats for nerds: the stream's codec, bitrate and sample rate, and what Automix is doing, under the artist. */
  showNerdStats: boolean;
}

interface PlayerState extends Settings {
  /** The whole timeline: what played, what plays, what's next (playback/queue/timeline.ts). */
  queue: QueueEntry[];
  /** The playing entry in [queue]; -1 when nothing is loaded. */
  index: number;
  nowPlaying: NowPlaying | null;
  status: PlayerStatus;
  error: string | null;
  /** Bumped whenever the audio element in front changes (crossfade swaps them). */
  deck: number;
  /** AutoMix's loop, while it runs: what it plans and when. */
  automixState: AutoDJSnapshot | null;
  /** A loop mix is playing from its decks: read the position from getPlayhead(), not the element. */
  mixing: boolean;
  /**
   * Auden Connect's mirror: another device of the account is the one playing, and this store shows its session
   * (queue, song, status, position through getPlayhead()) with no sound here. Every control goes to that device
   * (mirrorHooks) and nothing that follows the player (history, Discord, stats, a party) hears a mirrored song.
   */
  mirror: boolean;
  /** The mirrored device's volume (the local volume is left as it was). */
  mirrorVolume: number;

  /** Plays [tracks] as the context (an album, a playlist), from [startIndex]. */
  playTracks: (tracks: Track[], startIndex: number, source: QueueSource) => void;
  /** Plays one song on its own (search, home); AutoPlay carries on after it. */
  playTrack: (track: Track, source: QueueSource) => void;
  /** Plays a bare video id (the Debug page). */
  playVideo: (videoId: string, hint?: Partial<Omit<NowPlaying, 'videoId'>>) => Promise<void>;
  playNext: (tracks: Track[], source?: QueueSource | null) => void;
  addToQueue: (tracks: Track[], source?: QueueSource | null) => void;
  /** Tapping a row in Up Next (QueueTimeline.computeJumpOrder). */
  jumpTo: (index: number) => void;
  removeAt: (index: number) => void;
  /** Moves an upcoming row; both indices are into [queue] and after the playing one. */
  move: (from: number, to: number) => void;
  /** "Clear": the upcoming hand-queued tracks. The album or playlist and AutoPlay stay. */
  clearUpNext: () => void;
  next: () => void;
  previous: () => void;
  togglePlay: () => void;
  seek: (seconds: number) => void;
  toggleShuffle: () => void;
  cycleRepeat: () => void;
  setAutoplay: (on: boolean) => void;
  setVolume: (volume: number) => void;
  toggleMute: () => void;
  setCrossfade: (seconds: number) => void;
  setAutomix: (on: boolean) => void;
  setAutomixBars: (bars: number) => void;
  setShowNerdStats: (on: boolean) => void;
}

// ---- Settings, kept across visits ---------------------------------------------------

const SETTINGS_KEY = 'bitchord:player';
const DEFAULT_SETTINGS: Settings = {
  shuffle: false,
  repeat: 'off',
  autoplay: true,
  volume: 1,
  muted: false,
  crossfadeSec: 0,
  automix: false,
  automixBars: 16,
  showNerdStats: false,
};

function loadSettings(): Settings {
  try {
    return { ...DEFAULT_SETTINGS, ...(JSON.parse(localStorage.getItem(SETTINGS_KEY) ?? '{}') as Partial<Settings>) };
  } catch {
    return DEFAULT_SETTINGS;
  }
}

function saveSettings(state: Settings) {
  // While the DJ holds shuffle, repeat and AutoPlay off, what is kept is the listener's own (djPlayer.restore's result).
  const held = djHeld;
  // While mirroring, shuffle and repeat are the other device's: what is kept is this browser's own.
  if (mirrorStash) state = { ...state, ...mirrorStash };
  const shuffle = state.shuffle || !!held?.shuffle;
  const repeat = held && state.repeat === 'off' ? held.repeat : state.repeat;
  const autoplay = state.autoplay || !!held?.autoplay;
  const { volume, muted, crossfadeSec, automix, automixBars, showNerdStats } = state;
  localStorage.setItem(
    SETTINGS_KEY,
    JSON.stringify({ shuffle, repeat, autoplay, volume, muted, crossfadeSec, automix, automixBars, showNerdStats }),
  );
}

// ---- Decks: two elements, so a crossfade can play the next track under this one ----------
//
// Each deck's element plays YouTube or a file (playback/deck.ts); deck.media
// is the only way either engine is driven.

interface Deck {
  media: MediaDeck<YouTubeEngine, FileEngine>;
  audio: HTMLAudioElement;
  /** The crossfade's share of the volume, 0–1. */
  gain: number;
}

let decks: Deck[] | null = null;
let decksPromise: Promise<Deck[]> | null = null;
let active = 0;

/** The audio element in front, if playback has started at least once. */
export function getAudio(): HTMLAudioElement | null {
  return decks?.[active]?.audio ?? null;
}

/**
 * Where the song in front is, in seconds, and whether it's moving. Its
 * element, except while a loop mix plays it from a deck: then the mix's own
 * beat clock, which loops and follows the tempo ramp (the element is paused
 * underneath). The lyrics and the seek bars read this.
 */
export function getPlayhead(): { sec: number; paused: boolean } | null {
  const { mirror, mixing, nowPlaying } = usePlayer.getState();
  // A mirrored session has no element: the other device's position, carried forward by the clock.
  if (mirror) return mirrorHooks?.playhead() ?? null;
  const audio = getAudio();
  if (mixing && graph && nowPlaying) {
    const sec = graph.dj.mixPlayhead(nowPlaying.videoId);
    if (sec !== null) return { sec, paused: false };
  }
  return audio ? { sec: audio.currentTime, paused: audio.paused } : null;
}

/** The running Automix blend's beat (phase 0 on the beat), for the seek bar's pulse; null between blends. */
export function getMixBeat(): { phase: number | null } | null {
  if (usePlayer.getState().mirror) return null;
  return graph?.dj.mixBeat() ?? null;
}

function getDecks(): Promise<Deck[]> {
  decksPromise ??= Promise.resolve().then(() => {
    decks = [0, 1].map((n) => {
      const audio = new Audio();
      audio.preload = 'auto';
      // Media from another origin is silent inside an AudioContext unless it
      // is fetched with CORS (prompt §6, the Web Audio caveat). blob: URLs are same-origin.
      audio.crossOrigin = 'anonymous';
      const file = new FileEngine(audio);
      const deck: Deck = { media: new MediaDeck(file, () => createYouTubeEngine(deck)), audio, gain: 1 };
      file.onFatal = (id, message) => deckFailed(deck, id, message);
      deck.media.onFallback = (id, error) => {
        trackLog.w(`the linked file wouldn't play (${error instanceof Error ? error.message : String(error)}); playing from YouTube`, id);
      };
      bindElement(deck, n);
      return deck;
    });
    applyVolumes();
    // For browser-driven tests: the elements aren't in the DOM.
    if (import.meta.env.DEV) Object.defineProperty(window, '__bcAudio', { get: getAudio, configurable: true });
    return decks;
  });
  return decksPromise;
}

/**
 * A deck's YouTube engine, made on its first YouTube load. Shaka, YouTube.js
 * and googlevideo are the heavy part of the app; they load on the first
 * YouTube play, not with the page (prompt §3), and never in a session of
 * local files alone. The elements (and Automix's graph over them) don't wait
 * for it: they exist from the first play of anything.
 */
async function createYouTubeEngine(deck: Deck): Promise<YouTubeEngine> {
  const { YouTubeEngine } = await import('@/playback/youtube/engine');
  const engine = new YouTubeEngine(deck.audio);
  engine.onFatal = (videoId, message) => deckFailed(deck, videoId, message);
  engine.onRecovered = (track) => {
    if (decks?.[active] !== deck) return;
    // Keep YouTube Music's title and artist: without the queue's track the
    // video's own ("Doja Cat - Woman" by "dojacatVEVO") would replace them.
    const { queue, index } = usePlayer.getState();
    const entry = queue[index];
    applyTrack(track, entry?.track.videoId === track.videoId ? entry.track : undefined);
  };
  return engine;
}

/** The track in front can't go on, after whatever fallback its engine has. */
function deckFailed(deck: Deck, id: string, message: string) {
  if (decks?.[active] !== deck) return;
  trackLog.e(`stopped: ${message}`, id);
  usePlayer.setState({ status: 'error', error: message });
}

// ---- Which medium plays a track (playback/source.ts) -----------------------------------

const isOnline = () => typeof navigator === 'undefined' || navigator.onLine;

function mediumContext(preferStream = false): MediumContext {
  return {
    inParty: !!partyHooks?.inParty(),
    clip: clipBlob,
    local: getLocalResolver(),
    hasLink: getLinkProbe(),
    preferStream,
    offline: getOfflineProvider(),
    mixing: usePlayer.getState().automix && isOnline() && extensionReady(),
  };
}

/** Whether a YouTube track would play from a linked file now (Automix only mixes what plays from YouTube). */
function playsFromFile(track: Track): boolean {
  const probe = getLinkProbe();
  if (partyHooks?.inParty()) return false;
  return (!!probe && probe(track)) || offlineCopyPlays(track, mediumContext());
}

/**
 * The medium for [track]: at once when there's nothing to read (a YouTube
 * song with no file provider, a party, a missing clip), a promise when a file
 * has to be read first. The synchronous case keeps YouTube's path exactly as
 * it was.
 */
function mediumOf(track: Track, preferStream = false): Medium | Promise<Medium> {
  const plan = resolveMedium(track, mediumContext(preferStream));
  if (plan.kind !== 'file') return plan;
  return readMedium(plan).then((medium) => {
    if (medium.kind === 'youtube' && medium.fellBack) {
      trackLog.w(`the linked file couldn't be read (${medium.fellBack}); playing from YouTube`, track.videoId);
    }
    return medium;
  });
}

/** What the deck is asked for. A linked file falls back to YouTube only where YouTube can play. */
function deckMedium(medium: Exclude<Medium, { kind: 'unavailable' }>): DeckMedium {
  return medium.kind === 'file'
    ? { kind: 'file', blob: medium.blob, fallback: medium.fallback === 'youtube' && extensionReady() }
    : { kind: 'youtube' };
}

/**
 * Loads [track] on [deck] from its medium, unless the deck was loaded or
 * stopped since [since] (its version when the caller decided to load).
 */
async function loadOnDeck(
  deck: Deck,
  track: Track,
  options: DeckLoadOptions,
  since: number,
  preferStream = false,
): Promise<LoadedTrack> {
  const medium = await mediumOf(track, preferStream);
  if (deck.media.version !== since) throw new SupersededError('The deck moved on while the file was read');
  if (medium.kind === 'unavailable') throw new Error(medium.reason);
  return deck.media.load(deckMedium(medium), track.videoId, options, since);
}

function applyVolumes() {
  const { volume, muted } = usePlayer.getState();
  const level = muted ? 0 : Math.min(1, Math.max(0, volume));
  if (graph) {
    // Routed through Web Audio: the elements stay at 1 and the graph carries volume and the fade.
    const now = graph.ctx.currentTime;
    graph.master.gain.setTargetAtTime(level, now, 0.015);
    (decks ?? []).forEach((deck, n) => {
      deck.audio.volume = 1;
      graph!.decks[n]!.fader.gain.setTargetAtTime(deck.gain, now, 0.015);
    });
    return;
  }
  for (const deck of decks ?? []) deck.audio.volume = level * deck.gain;
}

// ---- AutoMix (playback/automix): the two decks routed through Web Audio, mixed like a DJ ----

let graph: AutomixGraph | null = null;
let graphPromise: Promise<AutomixGraph | null> | null = null;
/** The track AutoMix loaded on the back deck, for the handoff. */
let mixLoaded: { videoId: string; track: LoadedTrack } | null = null;
/**
 * A loop mix is running: the decks carry the sound and both elements are
 * paused or started by the mix, so their pause, waiting and ended events
 * aren't the user's and mustn't move the player.
 */
let holding = false;

/** Builds the Web Audio graph and the mix loop over the decks, once. */
function ensureAutomix(): Promise<AutomixGraph | null> {
  const all = decks;
  if (!all) return Promise.resolve(null);
  graphPromise ??= import('@/playback/automix').then(({ createAutomixGraph, analyzer }) => {
    const built = createAutomixGraph(
      all.map((d) => d.audio),
      (routed) => ({
        front: () => routed[active]!,
        back: () => routed[1 - active]!,
        current: () => {
          // A party doesn't mix: every member would blend on their own clock (the phone stops too).
          if (partyHooks?.inParty()) return null;
          const { queue, index, status, mirror } = usePlayer.getState();
          if (mirror) return null;
          const entry = queue[index];
          // Automix analyses YouTube audio: a local file or clip is outside it (playback/source.ts mixPolicy).
          if (!entry || sourceOf(entry.track) !== 'youtube') return null;
          // Nor a track playing from a linked file: the analysis is of the YouTube encode, which is a
          // different recording of the song (lead-in, length), so its cue points would be wrong here.
          if (all[active]!.media.mode === 'file') return null;
          return status === 'playing' ? queued(entry) : null;
        },
        next: () => {
          if (isMirrored()) return null;
          const plan = planNext(true);
          if (plan?.kind !== 'index') return null;
          const entry = plan.queue[plan.index]!;
          return sourceOf(entry.track) === 'youtube' && !playsFromFile(entry.track) ? queued(entry) : null;
        },
        loadBack: async (videoId) => {
          const back = all[1 - active]!;
          back.gain = 1;
          applyVolumes();
          const since = back.media.version;
          try {
            const track = await loadOnDeck(back, queuedTrack(videoId), { autoplay: false }, since, true);
            mixLoaded = { videoId, track };
            return true;
          } catch (error) {
            if (!isSuperseded(error)) {
              trackLog.w(`automix couldn't load the next track: ${String(error)}`, videoId);
            }
            return false;
          }
        },
        handoff: automixHandoff,
        sungSpans,
        hold: (on) => {
          holding = on;
          usePlayer.setState(on ? { status: 'playing', mixing: true } : { mixing: false });
        },
        finished: () => {
          void all[1 - active]!.media.stop().catch(() => {});
        },
      }),
      (snap) => usePlayer.setState({ automixState: snap }),
    );
    graph = built;
    applyVolumes();
    if (import.meta.env.DEV) Object.assign(window, { __bcAutomix: built, __bcAnalyzer: analyzer });
    return built;
  });
  return graphPromise;
}

function queued(entry: QueueEntry) {
  return { videoId: entry.track.videoId, title: entry.track.title, artist: artistNames(entry.track) };
}

/**
 * The queue's track for an id Automix hands back: the next one, else the
 * first with that id. A bare id (gone from the queue) is taken as YouTube's.
 */
function queuedTrack(videoId: string): Track {
  const plan = planNext(true);
  const next = plan?.kind === 'index' ? plan.queue[plan.index]?.track : undefined;
  if (next?.videoId === videoId) return next;
  const found = usePlayer.getState().queue.find((e) => e.track.videoId === videoId)?.track;
  return (
    found ?? { videoId, title: '', artists: [], album: null, durationSec: null, explicit: false, thumbnail: null, index: null, plays: null }
  );
}

/** How long the loop mix waits for a song's lyrics before planning from the vocal mask alone. */
const LYRICS_WAIT_MS = 8000;

/**
 * Where a queued song is sung, from its synced lyrics (the same lookup, and
 * cache, as the lyrics panel). Null when it has no synced lyrics or they
 * don't arrive in time; the loop mix then falls back on the vocal mask.
 */
async function sungSpans(videoId: string) {
  if (isSyntheticId(videoId)) return null;
  const { queue } = usePlayer.getState();
  const track = queue.find((e) => e.track.videoId === videoId)?.track;
  if (!track) return null;
  const [{ lookupLyrics }, { endMs, hasKnownEnd, isGap }, { sungSpansFromLines }] = await Promise.all([
    import('@/lyrics/repository'),
    import('@/lyrics/model'),
    import('@/playback/automix'),
  ]);
  const found = await Promise.race([
    lookupLyrics({
      videoId,
      title: track.title,
      artist: artistNames(track),
      durationMs: Math.round((track.durationSec ?? 0) * 1000),
      album: track.album?.name ?? null,
    }).catch(() => null),
    new Promise<null>((r) => setTimeout(() => r(null), LYRICS_WAIT_MS)),
  ]);
  if (!found?.synced) return null;
  return sungSpansFromLines(
    found.lines.map((l) => ({ timeMs: l.timeMs, endMs: hasKnownEnd(l) ? endMs(l) : null, blank: isGap(l) })),
  );
}

/** The blend's midpoint: the incoming track is the one playing now, as far as everything else knows. */
function automixHandoff() {
  if (!decks) return;
  const loaded = mixLoaded;
  mixLoaded = null;
  const plan = planNext(true);
  const entry = plan?.kind === 'index' ? plan.queue[plan.index] : undefined;
  if (!plan || plan.kind !== 'index' || !entry || !loaded || entry.track.videoId !== loaded.videoId) {
    trackLog.w('automix handoff found the queue moved; playing on', loaded?.videoId ?? null);
    graph?.dj.cancel();
    return;
  }
  active = 1 - active;
  usePlayer.setState((s) => ({ queue: plan.queue, index: plan.index, deck: s.deck + 1, status: 'playing' }));
  applyTrack(loaded.track, entry.track);
  afterTrackStarted();
  intent();
}

/** Starts or stops the mix loop to match the setting; the first start builds the graph. */
function syncAutomix() {
  if (!usePlayer.getState().automix || isMirrored()) {
    graph?.dj.stop();
    return;
  }
  void ensureAutomix().then((g) => {
    const { automix, automixBars } = usePlayer.getState();
    if (!g || !automix) return;
    void g.ctx.resume();
    g.dj.transitionBars = automixBars;
    g.dj.start();
  });
}

function extensionReady(): boolean {
  const detected = queryClient.getQueryData<DetectedExtension | null>(extensionQueryKey);
  return !!detected?.info.capabilities.includes('yt');
}

const artistNames = (track: Track) => track.artists.map((a) => a.name).join(', ');

function hintOf(track: Track): NowPlaying {
  return {
    videoId: track.videoId,
    source: sourceOf(track),
    shareable: isShareable(track),
    medium: null,
    title: track.title,
    artist: artistNames(track),
    // A song with an offline copy shows the cover saved with it: no request, no refusal, no network needed.
    artworkUrl: offlineCoverUrl(track.videoId) ?? track.thumbnail,
    durationSec: track.durationSec ?? 0,
    streamInfo: null,
    stream: null,
  };
}

// ---- Listen Together's two doors (playback/PartySync.kt) ----------------------------
//
// Every user action reports an intent through [partyHooks]; that is how the
// party hears about it. What the party does to this player goes through
// [partyPlayer] instead, which reports nothing, so a correction can never
// come back around as a control.

export interface PartyHooks {
  /** The user did something to playback, or the queue moved on by itself. */
  onIntent(): void;
  /** True when a resume must wait for the party's scheduled start (partySync starts it). */
  shouldDeferPlay(): boolean;
  /** A listener in a host-locked party: play/pause is theirs alone. True when handled. */
  onLockedTransport(playing: boolean): boolean;
  /** In a party, AutoPlay is the party's: one elected member tops up the shared queue. */
  inParty(): boolean;
}

let partyHooks: PartyHooks | null = null;
export function setPartyHooks(hooks: PartyHooks | null): void {
  partyHooks = hooks;
}
const intent = () => partyHooks?.onIntent();

// ---- Auden Connect's mirror (auden/connect/session.ts) ------------------------------------
//
// While another device of the account is the one playing, this store shows its session instead of its own
// (mirrorPlayer.show) and every control is sent to that device through [mirrorHooks]. Starting something new
// here (playTracks, playTrack, a DJ, a party's queue, a local file) ends the mirror first: the hook releases
// the other device and the player carries on as it always did.

export interface MirrorHooks {
  togglePlay(): void;
  next(): void;
  previous(): void;
  seek(seconds: number): void;
  toggleShuffle(): void;
  cycleRepeat(): void;
  setVolume(volume: number): void;
  toggleMute(): void;
  jumpTo(index: number): void;
  playNext(tracks: Track[]): void;
  addToQueue(tracks: Track[]): void;
  removeAt(index: number): void;
  move(from: number, to: number): void;
  clearUpNext(): void;
  /** The listener starts something new here: the mirror ends and the other device is released. */
  leave(): void;
  /** Where the other device's song is now. */
  playhead(): { sec: number; paused: boolean } | null;
}

let mirrorHooks: MirrorHooks | null = null;
export function setMirrorHooks(hooks: MirrorHooks | null): void {
  mirrorHooks = hooks;
}

/** True while the store shows another device's session: nothing may be recorded, reported or announced for it. */
export const isMirrored = (): boolean => usePlayer.getState().mirror;

/** The hooks, while mirrored: the controls' way out to the other device. */
const mirrored = (): MirrorHooks | null => (usePlayer.getState().mirror ? mirrorHooks : null);

/** shuffle and repeat as this browser had them before the mirror took them over. */
let mirrorStash: Pick<Settings, 'shuffle' | 'repeat'> | null = null;

/** A new context is about to take over: the mirror ends first (and releases the other device). */
function leaveMirror(): void {
  if (!usePlayer.getState().mirror) return;
  if (mirrorHooks) mirrorHooks.leave();
  else mirrorPlayer.leave(true);
}

// ---- Store ------------------------------------------------------------------------

/** Each load gets a number; an answer for an older one is dropped. */
let loadToken = 0;
/** Canonical positions of the queue before shuffle, for turning it off again. */
let canonical: Map<string, number> | null = null;
/** What a DJ session found and turned off (djPlayer.start); null when no session holds them. */
let djHeld: Pick<Settings, 'shuffle' | 'repeat' | 'autoplay'> | null = null;

export const usePlayer = create<PlayerState>()((set, get) => ({
  ...loadSettings(),
  queue: [],
  index: -1,
  nowPlaying: null,
  status: 'idle',
  error: null,
  deck: 0,
  automixState: null,
  mixing: false,
  mirror: false,
  mirrorVolume: 1,

  playTracks(tracks, startIndex, source) {
    if (!tracks.length) return;
    leaveMirror();
    leaveDj();
    const { queue, index, shuffle } = get();
    const built = buildContextQueue(queue, index, tracks, startIndex, source);
    if (shuffle) {
      canonical = positions(built.timeline);
      set({ queue: shuffledStartingOrder(built.timeline, built.startIndex) });
      void startAt(0);
    } else {
      canonical = null;
      set({ queue: built.timeline });
      void startAt(built.startIndex);
    }
    intent();
  },

  playTrack(track, source) {
    leaveMirror();
    leaveDj();
    const { queue, index } = get();
    canonical = null;
    set({ queue: buildOneOffQueue(queue, index, track, source) });
    void startAt(0);
    intent();
  },

  async playVideo(videoId, hint = {}) {
    get().playTrack(
      {
        videoId,
        title: hint.title ?? '',
        artists: hint.artist ? [{ name: hint.artist, id: null }] : [],
        album: null,
        durationSec: hint.durationSec ?? null,
        explicit: false,
        thumbnail: hint.artworkUrl ?? null,
        index: null,
        plays: null,
      },
      { title: 'Debug', type: 'other' },
    );
  },

  playNext(tracks, source = null) {
    if (mirrored()) return mirrored()!.playNext(tracks);
    insertUser(tracks, source, true);
    intent();
  },

  addToQueue(tracks, source = null) {
    if (mirrored()) return mirrored()!.addToQueue(tracks);
    insertUser(tracks, source, false);
    intent();
  },

  jumpTo(target) {
    if (mirrored()) return mirrored()!.jumpTo(target);
    const { queue, index } = get();
    if (target === index) return;
    const jumped = applyJump(queue, index, target);
    if (!jumped) return;
    set({ queue: jumped.timeline });
    void startAt(jumped.index);
    intent();
  },

  removeAt(at) {
    if (mirrored()) return mirrored()!.removeAt(at);
    const { queue, index } = get();
    if (at === index || at < 0 || at >= queue.length) return;
    set({ queue: queue.filter((_, i) => i !== at), index: at < index ? index - 1 : index });
    void refillAutoplay();
    intent();
  },

  move(from, to) {
    if (mirrored()) return mirrored()!.move(from, to);
    const { queue, index } = get();
    if (from <= index || to <= index || from >= queue.length || to >= queue.length || from === to) return;
    const next = [...queue];
    const [entry] = next.splice(from, 1);
    next.splice(to, 0, entry!);
    set({ queue: next });
    intent();
  },

  clearUpNext() {
    if (mirrored()) return mirrored()!.clearUpNext();
    const { queue, index } = get();
    const drop = new Set(upcomingUserQueueIndices(queue.map((e) => e.tier), index));
    set({ queue: queue.filter((_, i) => !drop.has(i)) });
    intent();
  },

  next() {
    if (mirrored()) return mirrored()!.next();
    void advance(false).then(intent);
  },

  previous() {
    if (mirrored()) return mirrored()!.previous();
    const { index, queue, repeat } = get();
    const audio = getAudio();
    if (audio && audio.currentTime > BACK_RESTARTS_AFTER_SEC) return get().seek(0);
    if (index > 0) void startAt(index - 1);
    else if (repeat === 'all' && queue.length > 1) void startAt(queue.length - 1);
    else return get().seek(0);
    intent();
  },

  togglePlay() {
    if (mirrored()) return mirrored()!.togglePlay();
    // A loop mix holds both elements paused: settle it onto a playing song first.
    if (graph?.dj.loopMixing) cancelCrossfade();
    const audio = getAudio();
    const { nowPlaying, status, index } = get();
    if (!nowPlaying) return;
    // A file is being read for the track just asked for; the old element must not start up under it.
    if (status === 'loading' && isReading()) return;
    const resuming = !audio || audio.paused || status === 'error';
    // A routed graph left suspended (no gesture when it was built) would play silence.
    if (resuming) void graph?.ctx.resume();
    // A listener in a party the host has locked: the press moves this device alone.
    if (partyHooks?.onLockedTransport(resuming)) return;
    if (!audio || status === 'error') {
      // A stopped or failed track starts again from where it was.
      void startAt(index, { startSec: audio?.currentTime ?? 0 });
    } else if (audio.paused) {
      // In a party, a resume waits for the instant the server schedules for everyone.
      if (!partyHooks?.shouldDeferPlay()) void audio.play().catch((e: unknown) => trackLog.w(`play() refused: ${String(e)}`));
    } else {
      cancelCrossfade();
      audio.pause();
    }
    intent();
  },

  seek(seconds) {
    if (mirrored()) return mirrored()!.seek(seconds);
    const audio = getAudio();
    if (!audio) return;
    cancelCrossfade();
    audio.currentTime = Math.max(0, Math.min(seconds, audio.duration || seconds));
    updatePositionState();
    intent();
  },

  toggleShuffle() {
    if (mirrored()) return mirrored()!.toggleShuffle();
    const { queue, index, shuffle } = get();
    const head = queue.slice(0, index + 1);
    const upcoming = queue.slice(index + 1);
    if (!shuffle) {
      canonical = positions(queue);
      const order = shuffledUpcomingOrder(upcoming.map((e) => e.tier));
      set({ shuffle: true, queue: [...head, ...order.map((i) => upcoming[i]!)] });
    } else {
      // Back to the order the queue had, by section; tracks added since keep their place at the end.
      const known = canonical ?? new Map<string, number>();
      const restored = upcoming
        .map((entry, i) => ({ i, at: known.get(entry.entryId) ?? Number.MAX_SAFE_INTEGER - upcoming.length + i }))
        .sort((a, b) => a.at - b.at)
        .map((x) => x.i);
      const order = sectionedOrder(restored, upcoming.map((e) => e.tier));
      canonical = null;
      set({ shuffle: false, queue: [...head, ...order.map((i) => upcoming[i]!)] });
    }
    saveSettings(get());
  },

  cycleRepeat() {
    if (mirrored()) return mirrored()!.cycleRepeat();
    const repeat: RepeatMode = { off: 'all', all: 'one', one: 'off' }[get().repeat] as RepeatMode;
    set({ repeat });
    saveSettings(get());
    if (repeat === 'off') void refillAutoplay(true);
  },

  setAutoplay(autoplay) {
    set({ autoplay });
    saveSettings(get());
    if (autoplay) void refillAutoplay(true);
    else if (!get().mirror) {
      // Turning it off takes the radio out of what's to come.
      const { queue, index } = get();
      set({ queue: queue.filter((e, i) => i <= index || e.tier !== 'autoplay') });
    }
  },

  setVolume(volume) {
    if (mirrored()) return mirrored()!.setVolume(volume);
    set({ volume: Math.min(1, Math.max(0, volume)), muted: false });
    applyVolumes();
    saveSettings(get());
  },

  toggleMute() {
    if (mirrored()) return mirrored()!.toggleMute();
    set({ muted: !get().muted });
    applyVolumes();
    saveSettings(get());
  },

  setCrossfade(crossfadeSec) {
    set({ crossfadeSec, automix: false });
    saveSettings(get());
    syncAutomix();
  },

  setAutomix(automix) {
    set({ automix, automixState: automix ? get().automixState : null });
    saveSettings(get());
    syncAutomix();
  },

  setAutomixBars(automixBars) {
    set({ automixBars });
    saveSettings(get());
    if (graph) graph.dj.transitionBars = automixBars;
  },


  setShowNerdStats(showNerdStats) {
    set({ showNerdStats });
    saveSettings(get());
  },
}));

// A clip (playback/clips.ts) is let go once it has left the queue and isn't playing.
usePlayer.subscribe((state, previous) => {
  // A mirrored queue is the other device's: it holds none of this browser's clips, and must not let them go.
  if (state.mirror || !heldClipCount() || (state.queue === previous.queue && state.nowPlaying === previous.nowPlaying)) return;
  const live = state.queue.map((e) => e.track.videoId);
  if (state.nowPlaying) live.push(state.nowPlaying.videoId);
  if (mixLoaded) live.push(mixLoaded.videoId);
  collectClips(live);
});

// For browser-driven tests, next to __bcAudio.
if (import.meta.env.DEV) {
  Object.assign(window, {
    __bcPlayer: usePlayer,
    // AutoMix's analysis self-test: expect BPM ≈ 123.93 and Camelot 8A.
    __automixSelfTest: () => import('@/playback/automix/analysis/selftest').then((m) => m.analysisSelfTest()),
  });
}

function positions(queue: readonly QueueEntry[]): Map<string, number> {
  return new Map(queue.map((e, i) => [e.entryId, i]));
}

function insertUser(tracks: Track[], source: QueueSource | null, isNext: boolean) {
  if (!tracks.length) return;
  const { queue, index } = usePlayer.getState();
  const entries = tracks.map((t) => asEntry(t, 'user', source));
  if (index < 0) {
    usePlayer.setState({ queue: entries });
    void startAt(0);
    return;
  }
  // Never between a DJ clip and the song it introduces (auden/dj/djQueue.ts); the playing index for anything else.
  const at = userQueueInsertionIndex(queue, introUnitEnd(queue, index), isNext);
  usePlayer.setState({ queue: [...queue.slice(0, at), ...entries, ...queue.slice(at)] });
}

// ---- Loading and moving through the queue -------------------------------------------

/** Loads queue[at] into the deck in front and plays it. `auto` marks a move the player made on its own. */
async function startAt(at: number, { startSec = 0, auto = false, autoplay = true } = {}): Promise<void> {
  const state = usePlayer.getState();
  let asked = state.queue[at];
  if (!asked) return;
  cancelCrossfade();
  const token = ++loadToken;
  reading = null;
  // Prefer music-only version: a music video waits for its song (15 s at most), which then stands in its row.
  // Meanwhile it is 'loading' and the old track is silenced, as while a file is read below.
  if (wantsMusicOnlyRow(asked)) {
    usePlayer.setState({ index: at, nowPlaying: hintOf(asked.track), status: 'loading', error: null });
    reading = token;
    decks?.[active]?.media.stop().catch(() => {});
    await musicOnlyRow(asked, () => token === loadToken);
    if (reading === token) reading = null;
    if (token !== loadToken) return;
    const { queue } = usePlayer.getState();
    const id = asked.entryId;
    at = queue.findIndex((e) => e.entryId === id);
    asked = queue[at];
    if (!asked) return;
  }
  const entry = asked;
  // A track the player reached on its own that can't play is skipped, as on the phone.
  const skipIfAuto = (message: string) => {
    if (!auto || !planNext(true)) return;
    trackLog.w(`skipping: ${message}`, entry.track.videoId);
    setTimeout(() => {
      if (token === loadToken) void advance(true);
    }, 1500);
  };

  // The medium before the extension's gate: a file needs no extension, and a
  // linked file beats YouTube (outside a party). Reading one takes a moment;
  // 'loading' meanwhile also keeps a crossfade from starting under it.
  let medium = mediumOf(entry.track);
  if (medium instanceof Promise) {
    usePlayer.setState({ index: at, nowPlaying: hintOf(entry.track), status: 'loading', error: null });
    // Silence what was playing and ignore its elements until the read settles: if the old track ended in
    // this window, the queue would move on and the track just asked for would never play.
    reading = token;
    decks?.[active]?.media.stop().catch(() => {});
    medium = await medium;
    if (reading === token) reading = null;
    if (token !== loadToken) return;
  }

  if (medium.kind === 'unavailable' || (medium.kind === 'youtube' && !extensionReady())) {
    const message = medium.kind === 'unavailable' ? medium.reason : 'YouTube playback needs the Auden extension.';
    usePlayer.setState({
      index: at,
      nowPlaying: { ...hintOf(entry.track), title: entry.track.title || 'Can’t play this here yet' },
      status: 'error',
      error: message,
    });
    // What was playing must not carry on under an error: a local file can be in front with no extension.
    decks?.forEach((d) => void d.media.stop().catch(() => {}));
    if (medium.kind === 'unavailable') skipIfAuto(message);
    return;
  }

  usePlayer.setState({ index: at, nowPlaying: hintOf(entry.track), status: 'loading', error: null });
  updateMediaSession(usePlayer.getState().nowPlaying);
  try {
    const all = await getDecks();
    const deck = all[active]!;
    all[1 - active]!.media.stop().catch(() => {});
    deck.gain = 1;
    applyVolumes();
    const track = await deck.media.load(deckMedium(medium), entry.track.videoId, { startSec, autoplay });
    if (token !== loadToken) return;
    applyTrack(track, entry.track);
    if (!autoplay) usePlayer.setState({ status: 'paused' });
    afterTrackStarted();
    syncAutomix();
  } catch (error) {
    if (token !== loadToken || isSuperseded(error)) return;
    const message = error instanceof Error ? error.message : String(error);
    usePlayer.setState({ status: 'error', error: message });
    skipIfAuto(message);
  }
}

type NextPlan = { kind: 'repeat-one' } | { kind: 'index'; queue: QueueEntry[]; index: number };

/**
 * What comes after the playing track, without doing it. `auto` is a track
 * ending (repeat-one replays it); a Next tap moves on regardless.
 */
function planNext(auto: boolean): NextPlan | null {
  const { queue, index, repeat } = usePlayer.getState();
  if (auto && repeat === 'one') return { kind: 'repeat-one' };
  if (index + 1 < queue.length) return { kind: 'index', queue, index: index + 1 };
  if (repeat !== 'off' && queue.length) {
    // Round again: the hand-queued tracks have had their turn; the context repeats.
    const tiers = queue.map((e) => e.tier);
    const played = new Set(playedUserQueueIndices(tiers, index));
    const kept = queue.filter((e, i) => !played.has(i) && (e.tier !== 'user' || i > index));
    return kept.length ? { kind: 'index', queue: kept, index: 0 } : null;
  }
  return null;
}

async function advance(auto: boolean): Promise<void> {
  let plan = planNext(auto);
  if (!plan && usePlayer.getState().autoplay) {
    await refillAutoplay(true);
    plan = planNext(auto);
  }
  if (!plan) {
    // The end: leave the last track loaded and paused, as a music app does.
    if (auto) usePlayer.setState({ status: 'paused' });
    return;
  }
  if (plan.kind === 'repeat-one') {
    const audio = getAudio();
    if (audio) {
      audio.currentTime = 0;
      void audio.play();
    }
    return;
  }
  usePlayer.setState({ queue: plan.queue });
  await startAt(plan.index, { auto });
}

/** Once a track is under way: prune hand-queued tracks already played, and top up AutoPlay. */
function afterTrackStarted() {
  const { queue, index } = usePlayer.getState();
  const played = new Set(playedUserQueueIndices(queue.map((e) => e.tier), index));
  if (played.size) {
    usePlayer.setState({
      queue: queue.filter((_, i) => !played.has(i)),
      index: index - [...played].filter((i) => i < index).length,
    });
  }
  void refillAutoplay();
}

// ---- Prefer music-only version (playback/musicOnly.ts) -------------------------------

/** Whether [entry]'s track should be turned into its song now. */
function wantsMusicOnlyRow(entry: QueueEntry): boolean {
  return wantsMusicOnly(entry.track, {
    inParty: !!partyHooks?.inParty(),
    // The DJ's lines name the song they introduce.
    dj: entry.source?.id === DJ_SOURCE.id && entry.source?.type === DJ_SOURCE.type,
    // What's on the device is what plays (a linked file, an offline copy).
    playsLocally: !!getLinkProbe()?.(entry.track) || !!getOfflineProvider()?.has(entry.track),
  });
}

/**
 * Looks up [entry]'s song and, if [allowed] still holds when it arrives, puts
 * it in the entry's place in the queue: same entry id, tier and source, only
 * the track replaced. False when the row stays as it was.
 */
async function musicOnlyRow(entry: QueueEntry, allowed: () => boolean): Promise<boolean> {
  const video = entry.track;
  const audio = await musicOnlyVersion(video);
  if (!audio || !allowed()) return false;
  const { queue } = usePlayer.getState();
  const at = queue.findIndex((e) => e.entryId === entry.entryId);
  if (at < 0 || queue[at]!.track.videoId !== video.videoId) return false;
  trackLog.i(`music only: playing '${audio.title}' (${audio.videoId})`, video.videoId);
  usePlayer.setState({ queue: queue.map((e, i) => (i === at ? { ...e, track: audio } : e)) });
  return true;
}

/**
 * The row after the playing one, turned into its song in the background, so
 * a crossfade or an Automix blend into it is planned against the song. Run
 * whenever it could have changed; a row already answered costs a map lookup.
 * Never a row a deck already holds or is loading for a transition: the
 * handoff checks that the next row is what it loaded.
 */
function convertNextRow() {
  const { queue, index, mirror } = usePlayer.getState();
  if (mirror) return;
  const plan = planNext(true);
  const next = plan?.kind === 'index' ? plan.queue[plan.index] : undefined;
  forgetExcept(new Set([queue[index]?.track.videoId, next?.track.videoId].filter((id): id is string => !!id)));
  if (!next || next.entryId === queue[index]?.entryId || !wantsMusicOnlyRow(next)) return;
  const id = next.entryId;
  void musicOnlyRow(next, () => {
    // Still the next row: not played meanwhile (a transition hands over without startAt), nor moved.
    const now = planNext(true);
    if (now?.kind !== 'index' || now.queue[now.index]?.entryId !== id) return false;
    return !fade && !mixLoaded && !graph?.dj.mixing;
  });
}

usePlayer.subscribe((state, previous) => {
  if (state.queue !== previous.queue || state.index !== previous.index) convertNextRow();
});

// A flip of the setting: the picture follows it at once, and the next row is converted (or no longer is).
// The playing track plays on as it is: switching it mid-song would restart it.
useMusicOnly.subscribe((state, previous) => {
  if (state.preferMusicOnly === previous.preferMusicOnly) return;
  followVideo();
  convertNextRow();
});

// ---- AutoPlay ---------------------------------------------------------------------

/**
 * How many AutoPlay tracks wait after the playing one (Autoplay.MAX_QUEUED_AUTOPLAY).
 * Topped back up on every track change, as on the phone: a suggestion that
 * starts playing is replaced by a new one at the end, rather than the whole
 * station arriving in one burst once the queue has run dry.
 */
const MAX_QUEUED_AUTOPLAY = 10;
let refilling: Promise<void> | null = null;
/** The track the last top-up was seeded from, so one track isn't asked about twice. */
let autoplaySeed: string | null = null;
/** Seeds that gave nothing new, so the same empty radio isn't asked for again. */
const exhaustedSeeds = new Set<string>();

/**
 * [anySeed]: when the playing track's radio has nothing new left, seed from
 * the latest earlier track in the queue whose radio still might (the empty
 * queue watchdog, ensureAutoplay).
 */
function refillAutoplay(force = false, anySeed = false): Promise<void> {
  const { autoplay, repeat, queue, index, mirror } = usePlayer.getState();
  if (mirror || !autoplay || repeat !== 'off' || index < 0) return Promise.resolve();
  // A party's AutoPlay goes through the server (party/partySync.ts), never straight onto this queue.
  if (partyHooks?.inParty()) return Promise.resolve();
  const needed = MAX_QUEUED_AUTOPLAY - queue.slice(index + 1).filter((e) => e.tier === 'autoplay').length;
  if (needed <= 0) return Promise.resolve();
  // Seeded from the playing track (loadAutoplayForCurrentTrack), not the queue's tail.
  const playing = queue[index]?.track;
  // A file or a clip has no YouTube radio.
  if (playing && sourceOf(playing) !== 'youtube') return Promise.resolve();
  let seed = playing;
  if (seed && exhaustedSeeds.has(seed.videoId) && anySeed) {
    seed = queue
      .slice(0, index)
      .reverse()
      .find((e) => sourceOf(e.track) === 'youtube' && !exhaustedSeeds.has(e.track.videoId))?.track;
  }
  if (!playing || !seed || exhaustedSeeds.has(seed.videoId)) return Promise.resolve();
  if (!force && autoplaySeed === seed.videoId) return Promise.resolve();
  autoplaySeed = seed.videoId;
  refilling ??= (async () => {
    try {
      const { fetchRadio } = await import('@/data/music/api');
      const { candidates, seedIsVideo } = await fetchRadio(seed.videoId);
      noteCuts(candidates);
      const current = usePlayer.getState();
      const extra = extendStation(
        current.queue.map((e) => e.track),
        candidates,
        needed,
        seedIsVideo,
      );
      if (!extra.length) {
        exhaustedSeeds.add(seed.videoId);
        return;
      }
      const source: QueueSource = { title: `${seed.title} Radio`, type: 'radio', id: `RDAMVM${seed.videoId}` };
      // Still on the same track? One that moved on gets its own top-up.
      if (current.queue[current.index]?.track.videoId !== playing.videoId || !current.autoplay || current.mirror) {
        autoplaySeed = null;
        return;
      }
      usePlayer.setState({ queue: [...current.queue, ...extra.map((t) => asEntry(t, 'autoplay', source))] });
      trackLog.i(`AutoPlay added ${extra.length} tracks from ${seed.title}'s radio`, seed.videoId);
    } catch (error) {
      trackLog.w(`AutoPlay couldn't load the radio: ${String(error)}`, seed.videoId);
      autoplaySeed = null;
    } finally {
      refilling = null;
    }
  })();
  return refilling;
}

/** How often an empty AutoPlay is asked for again while a track plays. */
const EMPTY_RETRY_MS = 15_000;
let lastEmptyRetry = 0;

/**
 * The watchdog for an AutoPlay that is on but has nothing after the playing
 * track. A top-up runs only when a track starts, so a radio that failed to
 * load (a network blip, the extension not ready yet), one dropped because the
 * track changed while it loaded, or a seed whose radio had nothing new left
 * the queue empty until the music stopped. Called as the track plays.
 */
function ensureAutoplay() {
  const { autoplay, repeat, queue, index, status, mirror } = usePlayer.getState();
  if (mirror || !autoplay || repeat !== 'off' || index < 0 || status !== 'playing' || partyHooks?.inParty()) return;
  if (index + 1 < queue.length || refilling) return;
  // A file or a clip has no radio to ask for (refillAutoplay).
  const playing = queue[index]?.track;
  if (playing && sourceOf(playing) !== 'youtube') return;
  const now = Date.now();
  if (now - lastEmptyRetry < EMPTY_RETRY_MS) return;
  lastEmptyRetry = now;
  trackLog.i('AutoPlay is on but nothing is queued; asking for the radio again', queue[index]?.track.videoId ?? null);
  void refillAutoplay(true, true);
}

// ---- Crossfade ----------------------------------------------------------------------

/**
 * The fade is stepped by a timer, not requestAnimationFrame: Chrome stops
 * frames altogether in a background tab, or a window covered by another,
 * which is where music usually plays from. The new track then sat at volume
 * 0 under the old one's end, silent until the tab was looked at again.
 * Timers keep running there (at most a second apart), and each step works
 * the volumes out from the clock, so a late step only lands further along.
 */
const FADE_STEP_MS = 40;

let fade: { token: number; frame: number; pending: boolean } | null = null;

function cancelCrossfade() {
  // AutoMix's blend or preloaded next track goes the same way as a fade: the deck in front plays on.
  if (graph && (graph.dj.mixing || mixLoaded)) {
    mixLoaded = null;
    graph.dj.cancel();
    if (decks) void decks[1 - active]!.media.stop().catch(() => {});
  } else graph?.dj.cancel();
  if (!fade) return;
  clearTimeout(fade.frame);
  const wasPending = fade.pending;
  fade = null;
  if (!decks) return;
  // Whichever deck isn't in front stops; the one in front plays on at full volume.
  void decks[1 - active]!.media.stop().catch(() => {});
  decks[active]!.gain = 1;
  applyVolumes();
  if (wasPending) loadToken++;
}

/** Called as the track in front plays: within the crossfade length of its end, start the next one underneath. */
function maybeCrossfade() {
  const state = usePlayer.getState();
  if (!decks || fade || state.mirror || state.automix || !state.crossfadeSec || state.status !== 'playing') return;
  const front = decks[active]!;
  const remaining = front.audio.duration - front.audio.currentTime;
  if (!Number.isFinite(remaining) || remaining > state.crossfadeSec || remaining < 1) return;
  const plan = planNext(true);
  if (!plan || plan.kind !== 'index') return;
  const frontEntry = state.queue[state.index];
  // A clip on either side is a hard cut, never a blend.
  if (frontEntry && mixPolicy(frontEntry.track, plan.queue[plan.index]!.track) === 'cut') return;

  const back = decks[1 - active]!;
  const entry = plan.queue[plan.index]!;
  const token = ++loadToken;
  fade = { token, frame: 0, pending: true };
  back.gain = 0;
  applyVolumes();
  trackLog.i(`crossfading into the next track over ${Math.round(remaining)}s`, entry.track.videoId);

  loadOnDeck(back, entry.track, { autoplay: true }, back.media.version)
    .then((track) => {
      if (!fade || fade.token !== token || !decks) return;
      fade.pending = false;
      const from = front;
      const to = back;
      // The new track is the one playing now, as far as everything else knows.
      active = 1 - active;
      usePlayer.setState((s) => ({ queue: plan.queue, index: plan.index, deck: s.deck + 1, status: 'playing' }));
      applyTrack(track, entry.track);
      afterTrackStarted();

      const length = Math.max(0.5, Math.min(from.audio.duration - from.audio.currentTime, usePlayer.getState().crossfadeSec)) * 1000;
      const started = performance.now();
      const step = () => {
        if (!fade || fade.token !== token) return;
        const t = Math.min(1, (performance.now() - started) / length);
        // Equal-power: the sum stays level through the middle.
        from.gain = Math.cos((t * Math.PI) / 2);
        to.gain = Math.sin((t * Math.PI) / 2);
        applyVolumes();
        if (t < 1 && !from.audio.ended) fade.frame = window.setTimeout(step, FADE_STEP_MS);
        else {
          fade = null;
          to.gain = 1;
          from.gain = 1;
          void from.media.stop().catch(() => {});
          applyVolumes();
        }
      };
      step();
    })
    .catch((error: unknown) => {
      if (!fade || fade.token !== token) return;
      trackLog.w(`crossfade load failed: ${String(error)}; the next track starts when this one ends`, entry.track.videoId);
      fade = null;
      back.gain = 1;
      void back.media.stop().catch(() => {});
      applyVolumes();
      // If the track in front already ended while this loaded, move on now.
      if (front.audio.ended) void advance(true);
    });
}

function applyTrack(track: LoadedTrack, from?: Track) {
  const nowPlaying: NowPlaying = {
    videoId: track.videoId,
    // Without the queue's track this is the YouTube engine's recovery: a YouTube song.
    source: from ? sourceOf(from) : 'youtube',
    shareable: from ? isShareable(from) : true,
    medium: track.stream.mode === 'file' ? 'file' : 'youtube',
    // YouTube Music metadata (from the queue) beats the video's own title.
    title: from?.title || track.title,
    // Auto-generated YouTube channels are named "Artist - Topic".
    artist: (from && artistNames(from)) || track.author.replace(/ - Topic$/, ''),
    artworkUrl: offlineCoverUrl(track.videoId) ?? from?.thumbnail ?? track.thumbnails[0]?.url ?? null,
    durationSec: track.durationSec || from?.durationSec || 0,
    streamInfo: `${track.format} via ${track.client}`,
    stream: { ...track.stream, client: track.client },
  };
  usePlayer.setState({ nowPlaying, error: null });
  updateMediaSession(nowPlaying);
  followVideo();
}

/** The picture player was ever started; until then there's nothing to stop (and Shaka needn't load to stop it). */
let videoFollowed = false;

/**
 * A music video's picture follows the deck in front (playback/youtube/videoDeck.ts,
 * loaded with the engine). A file has no picture: the last one is dropped.
 */
function followVideo() {
  const deck = decks?.[active];
  if (!deck || usePlayer.getState().mirror) return;
  // With "Prefer music-only version" on, a music video that plays as itself (no song for it) shows no picture.
  const picture = showsPicture(usePlayer.getState().nowPlaying?.videoId, !!partyHooks?.inParty());
  const engine = deck.media.mode === 'youtube' && picture ? deck.media.youtube : null;
  if (engine) {
    videoFollowed = true;
    void import('@/playback/youtube/videoDeck').then((m) => m.followVideo(engine, deck.audio));
  } else if (videoFollowed) {
    void import('@/playback/youtube/videoDeck').then((m) => m.stopVideo());
  }
}

const endedListeners = new Set<() => void>();
/** Called when the track in front plays to its end, before the queue moves on; a repeat-one loop included. */
export function onTrackEnded(listener: () => void): () => void {
  endedListeners.add(listener);
  return () => endedListeners.delete(listener);
}

/** The token of a startAt that is reading a file; while it is the current load, the elements' events are ignored. */
let reading: number | null = null;
const isReading = () => reading !== null && reading === loadToken;

/** The elements' own events are the source of truth for play state; only the deck in front speaks. */
function bindElement(deck: Deck, n: number) {
  const element = deck.audio;
  const inFront = () => active === n;
  const set = (status: PlayerStatus) => {
    // A mirrored session is the other device's: this element's events (it is being stopped) say nothing about it.
    if (!inFront() || holding || isReading() || usePlayer.getState().mirror) return;
    if (usePlayer.getState().status === 'error' && status !== 'playing') return;
    usePlayer.setState({ status });
  };
  element.addEventListener('playing', () => set('playing'));
  element.addEventListener('pause', () => {
    if (!element.ended) set('paused');
  });
  element.addEventListener('waiting', () => set('buffering'));
  element.addEventListener('ended', () => {
    if (!inFront() || holding || isReading() || usePlayer.getState().mirror) return;
    endedListeners.forEach((listener) => listener());
    // The track ran out before the crossfade's next one could start: give up
    // on the fade and play the next track here. Waiting on it was silence for
    // as long as that load took, which on a slow or failing load (each client
    // gets 15 s for first audio) came to half a minute.
    if (fade?.pending) cancelCrossfade();
    // AutoMix's outgoing track ran out before the blend's midpoint (a stall): the incoming one is already playing.
    if (graph?.dj.forceHandoff()) return;
    // The queue moving on by itself is the user's intent, as far as a party is concerned.
    void advance(true).then(intent);
  });
  element.addEventListener('timeupdate', () => {
    if (!inFront() || usePlayer.getState().mirror) return;
    maybeCrossfade();
    ensureAutoplay();
  });
  for (const event of ['durationchange', 'ratechange', 'seeked'] as const) {
    element.addEventListener(event, () => {
      if (inFront() && !usePlayer.getState().mirror) updatePositionState();
    });
  }
  element.addEventListener('play', () => {
    if (inFront() && !usePlayer.getState().mirror && 'mediaSession' in navigator) navigator.mediaSession.playbackState = 'playing';
  });
  element.addEventListener('pause', () => {
    if (inFront() && !usePlayer.getState().mirror && 'mediaSession' in navigator) navigator.mediaSession.playbackState = 'paused';
  });
}

// ---- Media Session (prompt §5.2 step 6) ---------------------------------------------

let handlersBound = false;

function updateMediaSession(track: NowPlaying | null) {
  if (!('mediaSession' in navigator)) return;
  const session = navigator.mediaSession;
  if (!track) {
    session.metadata = null;
    return;
  }
  session.metadata = new MediaMetadata({
    title: track.title,
    artist: track.artist,
    artwork: track.artworkUrl
      ? // Standard sizes only (lib/artwork.ts): an uncached one is what the CDN answers with 429.
      [120, 226, 544].map((px) => ({ src: artworkAtPixels(track.artworkUrl!, px), sizes: `${px}x${px}` }))
      : [],
  });

  if (handlersBound) return;
  handlersBound = true;
  const handle = (action: MediaSessionAction, handler: MediaSessionActionHandler | null) => {
    try {
      session.setActionHandler(action, handler);
    } catch {
      // Not every browser knows every action.
    }
  };
  const player = () => usePlayer.getState();
  // Through the store, so a party hears about them like any other press.
  // A loop mix holds the elements paused while it plays: that's playing, for the keys.
  const sounding = () => (isMirrored() ? usePlayer.getState().status === 'playing' : graph?.dj.loopMixing || getAudio()?.paused === false);
  const here = () => getPlayhead()?.sec ?? getAudio()?.currentTime ?? 0;
  handle('play', () => !sounding() && player().togglePlay());
  handle('pause', () => sounding() && player().togglePlay());
  handle('seekto', (details) => {
    if (details.seekTime !== undefined) player().seek(details.seekTime);
  });
  handle('seekbackward', (details) => player().seek(here() - (details.seekOffset ?? 10)));
  handle('seekforward', (details) => player().seek(here() + (details.seekOffset ?? 10)));
  handle('previoustrack', () => player().previous());
  handle('nexttrack', () => player().next());
}

function updatePositionState() {
  if (isMirrored()) return;
  const audio = getAudio();
  if (!audio || !('mediaSession' in navigator) || !Number.isFinite(audio.duration) || audio.duration <= 0) return;
  try {
    navigator.mediaSession.setPositionState({
      duration: audio.duration,
      playbackRate: audio.playbackRate,
      position: Math.min(audio.currentTime, audio.duration),
    });
  } catch {
    // Position past duration during a seek; the next event corrects it.
  }
}

// ---- The party's door (party/partySync.ts): moves the player, reports nothing ----------

const PARTY_SOURCE: QueueSource = { title: 'Listen Together', type: 'queue' };

export const partyPlayer = {
  /**
   * Puts the party's running order on the player at [index], at [startSec]; starts it only when [play].
   * [source] labels the rows: the party's by default, none for a session handed over by Auden Connect.
   */
  load(tracks: Track[], tiers: QueueEntry['tier'][], index: number, startSec: number, play: boolean, source: QueueSource | null = PARTY_SOURCE): Promise<void> {
    // The party's queue replaces the DJ's, as a new context does (and ends a mirror: the party is played here).
    leaveMirror();
    leaveDj();
    canonical = null;
    usePlayer.setState({ queue: tracks.map((t, i) => asEntry(t, tiers[i] ?? 'user', source)) });
    return startAt(index, { startSec, autoplay: play });
  },
  play(): void {
    const audio = getAudio();
    if (audio?.paused) void audio.play().catch((e: unknown) => trackLog.w(`party play() refused: ${String(e)}`));
  },
  pause(): void {
    const audio = getAudio();
    if (audio && !audio.paused) {
      cancelCrossfade();
      audio.pause();
    }
  },
  seekTo(seconds: number): void {
    const audio = getAudio();
    if (!audio) return;
    cancelCrossfade();
    audio.currentTime = Math.max(0, seconds);
    updatePositionState();
  },
  /** Replaces everything after the playing track; the playing track is left alone. */
  setUpcoming(tracks: Track[], tiers: ('user' | 'autoplay')[]): void {
    const { queue, index } = usePlayer.getState();
    if (index < 0) return;
    usePlayer.setState({ queue: [...queue.slice(0, index + 1), ...tracks.map((t, i) => asEntry(t, tiers[i] ?? 'user', PARTY_SOURCE))] });
  },
};

// ---- The mirror's door (auden/connect/session.ts): shows another device's session, plays nothing --------

/** What the other device plays, as the store shows it. */
export interface MirrorView {
  tracks: Track[];
  index: number;
  playing: boolean;
  shuffle: boolean;
  repeat: RepeatMode;
  volume: number;
}

const sameTrack = (a: Track, b: Track) =>
  a.videoId === b.videoId && a.title === b.title && a.thumbnail === b.thumbnail && a.durationSec === b.durationSec && artistNames(a) === artistNames(b);

/** The queue a view makes: ids from the song and its n-th appearance (so a row keeps its key as others come and go), rows reused when unchanged. */
function mirrorQueue(tracks: readonly Track[], before: readonly QueueEntry[]): QueueEntry[] {
  const seen = new Map<string, number>();
  const old = new Map(before.map((e) => [e.entryId, e]));
  const next = tracks.map((track) => {
    const n = seen.get(track.videoId) ?? 0;
    seen.set(track.videoId, n + 1);
    const entryId = `mirror:${track.videoId}:${n}`;
    const held = old.get(entryId);
    return held && sameTrack(held.track, track) ? held : ({ entryId, track, tier: 'user', source: null } satisfies QueueEntry);
  });
  return next.length === before.length && next.every((e, i) => e === before[i]) ? (before as QueueEntry[]) : next;
}

/** Local sound stops, and the player stops following anything of its own. */
function enterMirror(): void {
  leaveDj();
  cancelCrossfade();
  loadToken++;
  reading = null;
  holding = false;
  mixLoaded = null;
  graph?.dj.stop();
  decks?.forEach((d) => void d.media.stop().catch(() => {}));
  if (videoFollowed) void import('@/playback/youtube/videoDeck').then((m) => m.stopVideo());
  const { shuffle, repeat } = usePlayer.getState();
  mirrorStash = { shuffle, repeat };
  canonical = null;
}

export const mirrorPlayer = {
  /** Shows [view]; the first call enters the mirror. Calling it again with a newer view updates in place. */
  show(view: MirrorView): void {
    if (!usePlayer.getState().mirror) enterMirror();
    const s = usePlayer.getState();
    const queue = mirrorQueue(view.tracks, s.mirror ? s.queue : []);
    const track = queue[view.index]?.track;
    let nowPlaying: NowPlaying | null = null;
    if (track) {
      const hint = hintOf(track);
      const held = s.mirror ? s.nowPlaying : null;
      nowPlaying = held && held.videoId === hint.videoId && held.title === hint.title && held.artist === hint.artist && held.artworkUrl === hint.artworkUrl && held.durationSec === hint.durationSec ? held : hint;
    }
    usePlayer.setState({
      mirror: true,
      queue,
      index: view.index,
      nowPlaying,
      status: nowPlaying ? (view.playing ? 'playing' : 'paused') : 'idle',
      error: null,
      shuffle: view.shuffle,
      repeat: view.repeat,
      mirrorVolume: view.volume,
      mixing: false,
      automixState: null,
      // The position is a new reading: whatever draws it from getPlayhead() starts over.
      deck: s.deck + 1,
    });
    if (nowPlaying !== s.nowPlaying || !s.mirror) updateMediaSession(nowPlaying);
    if ('mediaSession' in navigator) {
      navigator.mediaSession.playbackState = nowPlaying ? (view.playing ? 'playing' : 'paused') : 'none';
      const head = mirrorHooks?.playhead();
      if (nowPlaying && head && nowPlaying.durationSec > 0) {
        try {
          navigator.mediaSession.setPositionState({ duration: nowPlaying.durationSec, playbackRate: 1, position: Math.min(head.sec, nowPlaying.durationSec) });
        } catch {
          // A position past the end while the next reading is on its way.
        }
      }
    }
  },

  /**
   * Ends the mirror. This browser's own shuffle and repeat come back. With [clear] the shown session goes too (a new
   * context is about to take its place); otherwise it stays as a paused queue (the other device's session ended).
   */
  leave(clear = false): void {
    if (!usePlayer.getState().mirror) return;
    const stash = mirrorStash;
    mirrorStash = null;
    usePlayer.setState({
      mirror: false,
      ...(stash ?? {}),
      ...(clear ? { queue: [], index: -1, nowPlaying: null, status: 'idle' as const, error: null } : { status: 'paused' as const }),
      deck: usePlayer.getState().deck + 1,
    });
    if (clear) updateMediaSession(null);
    if ('mediaSession' in navigator) navigator.mediaSession.playbackState = clear ? 'none' : 'paused';
  },
};

// ---- Shuffle's canonical order, for Connect's `order` ------------------------------------

/** [entries] as the queue stood before the shuffle (tracks added since at the end), for the protocol's `order`. */
export function canonicalOrderOf(entries: readonly QueueEntry[]): QueueEntry[] {
  const known = canonical;
  if (!known) return [...entries];
  return entries
    .map((entry, i) => ({ entry, at: known.get(entry.entryId) ?? Number.MAX_SAFE_INTEGER - entries.length + i }))
    .sort((a, b) => a.at - b.at)
    .map((x) => x.entry);
}

/** Makes [order] (videoIds, from another device) this queue's pre-shuffle order, so turning shuffle off lands on the same list. */
export function adoptShuffleOrder(order: readonly string[]): void {
  const { queue } = usePlayer.getState();
  const free = new Map<string, number[]>();
  order.forEach((id, i) => free.set(id, [...(free.get(id) ?? []), i]));
  canonical = new Map(queue.map((e, i) => [e.entryId, free.get(e.track.videoId)?.shift() ?? order.length + i]));
}

// ---- The DJ's door (auden/dj): its own queue, with shuffle, repeat and AutoPlay held off ----
//
// The DJ's order is the whole point (dev/auden/dj/DjSession.kt): shuffle would
// move its lines away from their songs, repeat would replay them, AutoPlay
// would fill in ahead of the sets it appends. They go off for the session and
// come back as they were when it ends: on a Stop (djPlayer.restore), or when
// the listener plays something else (playTracks, playTrack, playVideo, a
// party's queue), which tells the session first (onBeforeNewContext). Its
// calls report no intent: the DJ never runs in a party.

/** For the UI alone ("Playing from Auden DJ"): the DJ knows its own entries by entryId. */
export const DJ_SOURCE: QueueSource = { title: 'Auden DJ', type: 'other', id: 'dj' };

export interface DjStartOptions {
  /** Start the opening playing (the default); false loads it paused. */
  play?: boolean;
}

/** The queue entry each DjEntry became, for djPlayer.entryIdsOf. */
const djEntryIds = new WeakMap<DjEntry, string>();
const djStartListeners = new Set<(entryId: string) => void>();
const djLeaveListeners = new Set<() => void>();

function djQueueEntries(entries: readonly DjEntry[]): QueueEntry[] {
  return entries.map((entry) => {
    const queued = asEntry(entry.track, 'context', DJ_SOURCE);
    djEntryIds.set(entry, queued.entryId);
    return queued;
  });
}

/** A new context is about to replace the DJ's queue: the session ends first, and the settings come back. */
let djContextEpoch = 0;
function leaveDj() {
  // Counted even when no session holds the settings yet: a DJ that is still starting (loading its pool, rendering its
  // opening) compares this before it takes the queue, and gives way if the listener played something meanwhile.
  djContextEpoch++;
  if (!djHeld) return;
  try {
    djLeaveListeners.forEach((listener) => listener());
  } finally {
    // The new context's first track tops AutoPlay up from itself: no refill seeded from the old one.
    restoreDjSettings(false);
  }
}

/** Puts back what the session turned off, and only if the listener hasn't set it themselves since (DjSession.close). */
function restoreDjSettings(refill: boolean) {
  const held = djHeld;
  if (!held) return;
  djHeld = null;
  const state = usePlayer.getState();
  const autoplay = state.autoplay || held.autoplay;
  // Shuffle comes back as the setting: what is left of the DJ's queue plays on in its order, the next context shuffles.
  usePlayer.setState({
    shuffle: state.shuffle || held.shuffle,
    repeat: state.repeat === 'off' ? held.repeat : state.repeat,
    autoplay,
  });
  saveSettings(usePlayer.getState());
  if (refill && autoplay && !state.autoplay) void refillAutoplay(true);
}

const nextEntryId = (): string | undefined => {
  const { queue, index } = usePlayer.getState();
  return queue[index + 1]?.entryId;
};

/** After a DJ edit: a crossfade or Automix still loading what was next would hand over to a track no longer next. */
function djEdited(nextBefore: string | undefined) {
  if (nextEntryId() !== nextBefore && (fade?.pending || mixLoaded)) cancelCrossfade();
}

/** The queue had run out (or failed at its end) before the DJ's next set arrived: play on from it. */
function resumeIfStalled(hadNext: boolean, at: number) {
  const { index, status } = usePlayer.getState();
  if (hadNext || index < 0 || at < 0) return;
  if (getAudio()?.ended || status === 'error') void startAt(at);
}

// Tells the DJ which entry started (DjSession.entered): once the update that moved there is done, so a
// listener may queue the next set from it.
usePlayer.subscribe((state, previous) => {
  if (state.mirror || !djHeld || !djStartListeners.size || (state.index === previous.index && state.queue === previous.queue)) return;
  const entryId = state.queue[state.index]?.entryId;
  if (!entryId || entryId === previous.queue[previous.index]?.entryId) return;
  queueMicrotask(() => {
    if (djHeld) djStartListeners.forEach((listener) => listener(entryId));
  });
});

export const djPlayer = {
  /**
   * Starts a DJ session's queue with [entries] (the opening clip first): shuffle, repeat and AutoPlay
   * go off until [restore]; the listener's upcoming hand-queued songs are kept after the opening's
   * clip and song. False, and nothing touched, in a party (hosting or joined) or with no entries.
   */
  start(entries: DjEntry[], { play = true }: DjStartOptions = {}): boolean {
    if (!entries.length || partyHooks?.inParty()) return false;
    leaveMirror();
    const state = usePlayer.getState();
    // A restart keeps what the first start found.
    djHeld ??= { shuffle: state.shuffle, repeat: state.repeat, autoplay: state.autoplay };
    canonical = null;
    usePlayer.setState({
      shuffle: false,
      repeat: 'off',
      autoplay: false,
      queue: djStartQueue(djQueueEntries(entries), state.queue, state.index),
      index: 0,
    });
    saveSettings(usePlayer.getState());
    void startAt(0, { autoplay: play });
    return true;
  },

  /** Queues a set at the end of the DJ's queue (ahead of any AutoPlay rows). Returns the new entries' ids. */
  append(entries: DjEntry[]): string[] {
    if (!djHeld || !entries.length) return [];
    const { queue, index } = usePlayer.getState();
    const before = nextEntryId();
    const fresh = djQueueEntries(entries);
    const at = autoplaySectionStart(queue.map((e) => e.tier), index);
    usePlayer.setState({ queue: [...queue.slice(0, at), ...fresh, ...queue.slice(at)] });
    djEdited(before);
    resumeIfStalled(before !== undefined, at);
    return fresh.map((e) => e.entryId);
  },

  /**
   * A steer (DjSession.steer): drops the upcoming entries among [removeEntryIds] (the playing one stays)
   * and puts [entries] after the playing entry and the listener's songs queued after it. With [cut] the
   * new set plays at once; listener songs it jumps over wait after its opening clip and song.
   */
  replaceUpcoming(removeEntryIds: Iterable<string>, entries: DjEntry[], { cut = false }: { cut?: boolean } = {}): { entryIds: string[]; dropped: string[] } {
    if (!djHeld) return { entryIds: [], dropped: [] };
    const { queue, index } = usePlayer.getState();
    const before = nextEntryId();
    const fresh = djQueueEntries(entries);
    const plan = steerQueue(queue, index, new Set(removeEntryIds), fresh, cut);
    usePlayer.setState({ queue: plan.queue });
    if (plan.play !== null) void startAt(plan.play);
    else {
      djEdited(before);
      resumeIfStalled(before !== undefined, fresh.length ? plan.queue.findIndex((e) => e.entryId === fresh[0]!.entryId) : -1);
    }
    return { entryIds: fresh.map((e) => e.entryId), dropped: plan.dropped };
  },

  /**
   * Takes [entryIds] out of the queue (a Stop passes the DJ's clips: its songs play on). A playing one
   * is skipped to what follows it, unless it is the last thing queued.
   */
  remove(entryIds: Iterable<string>): void {
    const ids = new Set(entryIds);
    const { queue, index, status } = usePlayer.getState();
    if (!ids.size) return;
    const before = nextEntryId();
    const plan = removeFromQueue(queue, index, ids);
    if (plan.queue.length === queue.length) return;
    usePlayer.setState({ queue: plan.queue, index: plan.index });
    if (plan.skip) void startAt(plan.index, { autoplay: status !== 'paused' });
    else djEdited(before);
  },

  /** The queue entry ids [entries] were given when queued (null for one not queued by this door). */
  entryIdsOf(entries: readonly DjEntry[]): (string | null)[] {
    return entries.map((entry) => djEntryIds.get(entry) ?? null);
  },

  /** The playing entry's id; null when nothing is loaded. */
  playingEntryId(): string | null {
    const { queue, index } = usePlayer.getState();
    return queue[index]?.entryId ?? null;
  },

  /** The ids of everything after the playing entry, in order: the session's and the listener's. */
  upcomingEntryIds(): string[] {
    const { queue, index } = usePlayer.getState();
    return queue.slice(index + 1).map((e) => e.entryId);
  },

  /** Bumps each time the listener (or a party) starts a new context; a starting DJ gives way if it changed. */
  contextEpoch(): number {
    return djContextEpoch;
  },

  /** A session holds the settings (between start and restore). */
  isActive(): boolean {
    return djHeld !== null;
  },

  /** Puts shuffle, repeat and AutoPlay back as start found them (a Stop). Safe to call twice. */
  restore(): void {
    restoreDjSettings(true);
  },

  /** Called with each entry's id as it becomes the playing one, while a session is active (its own and the listener's). */
  onEntryStart(listener: (entryId: string) => void): () => void {
    djStartListeners.add(listener);
    return () => djStartListeners.delete(listener);
  },

  /**
   * Called synchronously when the listener plays something else (playTracks, playTrack, playVideo,
   * a party's queue) during a session, before the new queue is built: the session stops there. The
   * settings are restored right after, whether or not the listener calls restore.
   */
  onBeforeNewContext(listener: () => void): () => void {
    djLeaveListeners.add(listener);
    return () => djLeaveListeners.delete(listener);
  },
};

// For browser-driven checks (a fake DJ voice through the real queue), next to __bcPlayer.
if (import.meta.env.DEV && typeof window !== 'undefined') Object.assign(window, { __bcDjPlayer: djPlayer });

// ---- The last session, restored after a reload (state/lastSession.ts) ---------------------------

export const sessionPlayer = {
  /** The shuffle's canonical positions by entryId, so turning shuffle off after a reload restores the list. */
  canonical(): [string, number][] | null {
    return canonical ? [...canonical] : null;
  },
  /**
   * Puts a remembered session back on the player, paused at [startSec]: its rows as they were (tier, source and
   * all), and the song loaded but not started. Does nothing when something is already queued.
   */
  restore(entries: QueueEntry[], index: number, startSec: number, order: [string, number][] | null): Promise<void> {
    if (usePlayer.getState().queue.length > 0 || usePlayer.getState().mirror) return Promise.resolve();
    canonical = order ? new Map(order) : null;
    usePlayer.setState({ queue: entries });
    return startAt(index, { startSec, autoplay: false });
  },
  /** Loads the restored song again, paused at [startSec], when it failed to load and nothing else has started since. */
  retry(videoId: string, startSec: number): Promise<void> {
    const s = usePlayer.getState();
    if (s.mirror || s.status !== 'error' || s.nowPlaying?.videoId !== videoId || s.queue[s.index]?.track.videoId !== videoId) return Promise.resolve();
    return startAt(s.index, { startSec, autoplay: false });
  },
};
