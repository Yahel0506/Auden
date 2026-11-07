import { create } from 'zustand';
import { searchSongs } from '@/data/music/api';
import type { Track } from '@/data/music/types';
import { registerClip, releaseClip } from '@/playback/clips';
import { clipTrackId } from '@/playback/source';
import { djPlayer } from '@/state/player';
import { useParty } from '@/party/listenTogether';
import { DjLedger, INITIAL_SPOKEN_IN_A_ROW, type DjEntry, nextSpokenInARow, planNextDue, shouldSpeak, unqueuedClips } from './djQueue';
import { type DjBlock, type DjRequest, type DjTrack, djTrack, leadArtist, themeKey, understood } from './model';
import { namesGenre, parseRequest } from './parser';
import { phrasesFor } from './phrases';
import { type Pool, djHistory } from './pool';
import { DjPlanner, MIN_BLOCK } from './planner';
import { useDjSettings } from './settings';
import { merge as mergeTaste, profile as tasteProfile, queries as tasteQueries, type Hit, type Query } from './taste';
import { type Clip, speak } from './voice';
import { DjWriter } from './writer';

/**
 * The DJ session (Android DjSession.kt, on the web player's queue): plans a set at a time from the listener's music,
 * says a line before it (spoken through the voice proxy when it is there, always shown as words), queues the clip and
 * the songs as ordinary entries, and plans the next set when the last one starts. A clip is a hard cut: the player
 * never mixes a voice into the music. Last.fm genres and YouTube Music radio discoveries are left out (documented).
 */

export type DjPhase = 'idle' | 'starting' | 'on';

export interface DjState {
  phase: DjPhase;
  /** What the DJ is saying (or just said), for the screen. */
  line: string | null;
  /** The clip that is playing now: its loudness shape and length, for the ring. */
  voice: { envelope: Float32Array; durationMs: number; clipEntryId: string } | null;
  /** Why it could not start, or a note ("the voice isn't available: words only"). */
  notice: string | null;
  /** The DJ's current theme, as a short label for the menu. */
  theme: string | null;
  /**
   * The DJ is choosing: a request is in (the first one, or a steer) and its first words are not yet (Android DjSession's
   * `pending`). The DJ's screen shows for it, whatever is playing.
   */
  pending: boolean;
  /** What the listener asked for while it is pending, shown in place of the last set's theme (Android: request.ifBlank { null }). */
  request: string | null;
  /** The line has no voice (a set without a clip, or a voice that did not come through): it is shown over the usual player. */
  wordsOnly: boolean;
}

export const useDj = create<DjState>(() => ({ phase: 'idle', line: null, voice: null, notice: null, theme: null, pending: false, request: null, wordsOnly: false }));

// For browser-driven checks, next to __bcPlayer (the DJ's screen follows this state).
if (import.meta.env.DEV && typeof window !== 'undefined') Object.assign(window, { __bcDj: useDj });

/** Everything a run needs from the outside, so it can be tested: the pool, the voice, search, time. */
export interface DjDeps {
  loadPool: () => Promise<Pool>;
  speak: typeof speak;
  search: (query: string) => Promise<Track[]>;
  now: () => number;
  rand: () => number;
  /** How long the opening may wait for its voice before the DJ goes words-only (Android WAITED_RENDER_MS). */
  openingBudgetMs: number;
}

const defaultDeps = (loadPool: DjDeps['loadPool']): DjDeps => ({ loadPool, speak, search: searchSongs, now: Date.now, rand: Math.random, openingBudgetMs: 12_000 });

interface Run {
  token: number;
  pool: Pool;
  byId: Map<string, DjTrack>;
  planner: DjPlanner;
  writer: DjWriter;
  ledger: DjLedger;
  registered: Set<string>;
  previous: DjBlock | null;
  nextSet: number;
  spokenInARow: number;
  entryTrack: Map<string, DjTrack>;
  /** Words shown when the first entry of a set starts (a set with no clip). */
  captions: Map<number, string>;
  deps: DjDeps;
  language: 'es' | 'en';
  stopListening: Array<() => void>;
  /** An append is being planned/rendered (one at a time). */
  planning: boolean;
  /** Bumped by each steer: a render that started before it is stale when it finishes and is thrown away. */
  steers: number;
}

let run: Run | null = null;
let tokenSeq = 0;
let clipSeq = 0;

const alive = (r: Run) => run === r && useDj.getState().phase !== 'idle';

function clipTrack(clipId: string, clip: Clip): Track {
  return { videoId: clipTrackId(clipId), source: 'clip', title: 'Auden DJ', artists: [{ name: 'Auden DJ', id: null }], album: null, durationSec: clip.durationMs / 1000, explicit: false, thumbnail: null, index: null, plays: null };
}

/** The sentence for a set: the opening for the first, an introduction for the rest (and sometimes nothing). */
function lineFor(r: Run, block: DjBlock, first: boolean): string {
  const request = r.planner.request;
  const text = first ? r.writer.opening(new Date(r.deps.now()).getHours(), block, request, r.planner.relaxed) : r.writer.blockIntro(block, r.previous);
  return text.trim();
}

async function renderSet(r: Run, block: DjBlock, opts: { first: boolean; cut: boolean }): Promise<{ entries: DjEntry[]; line: string | null }> {
  const setIndex = r.nextSet++;
  const songs: DjEntry[] = block.tracks.flatMap((t) => {
    const track = r.pool.playable.get(t.id);
    return track ? [{ track, setIndex }] : [];
  });
  songs.forEach((e) => r.entryTrack.set(e.track.videoId, r.byId.get(e.track.videoId)!));
  const speakIt = shouldSpeak({ spokenInARow: r.spokenInARow, rand: r.deps.rand, cut: opts.cut || opts.first });
  r.spokenInARow = nextSpokenInARow(r.spokenInARow, speakIt);
  if (!speakIt) return { entries: songs, line: null };
  const line = lineFor(r, block, opts.first);
  if (!line) return { entries: songs, line: null };
  r.captions.set(setIndex, line);
  let clip: DjEntry | null = null;
  const settings = useDjSettings.getState();
  if (settings.voice) {
    const result = await r.deps.speak(line, r.language, opts.first ? { timeoutMs: r.deps.openingBudgetMs } : {});
    if (!alive(r)) return { entries: [], line: null };
    if ('clip' in result) {
      const clipId = `dj-${r.token}-${++clipSeq}`;
      registerClip(clipId, result.clip.blob);
      r.registered.add(clipId);
      clipEnvelopes.set(clipId, result.clip);
      clip = { track: clipTrack(clipId, result.clip), clipId, setIndex };
    } else if (result.failed !== 'no_key') {
      useDj.setState({ notice: voiceNotice(result.failed) });
    } else {
      useDj.setState({ notice: voiceNotice('no_key') });
    }
  }
  return { entries: clip ? [clip, ...songs] : songs, line };
}

const clipEnvelopes = new Map<string, Clip>();
const voiceNotice = (failed: string): string =>
  failed === 'no_key' ? 'The DJ’s voice isn’t available here: you’ll see what it says.' : failed === 'rate_limited' ? 'The voice is busy: the DJ will show its words.' : 'The voice didn’t come through: the DJ will show its words.';

function theme(block: DjBlock): string {
  return themeKey(block.theme).split(':')[0] ?? '';
}

/** Plans one set and queues it (append) or cuts to it (steer). Never throws into the player. */
async function planAndQueue(r: Run, how: { kind: 'append' } | { kind: 'steer'; cut: boolean }): Promise<boolean> {
  if (how.kind === 'append' && r.planning) return false;
  // A steer supersedes whatever was being planned: it takes a new number, and anything older is stale when it finishes.
  const mine = how.kind === 'steer' ? ++r.steers : r.steers;
  if (how.kind === 'append') r.planning = true;
  try {
    const block = r.planner.next();
    if (!block) {
      // Nothing left to make a set from (a small library, or everything played): the DJ steps aside, and what is queued
      // plays out with AutoPlay and the listener's settings back, rather than leaving the player to run dry.
      if (how.kind === 'append' && alive(r)) {
        useDj.setState({ notice: 'The DJ has played everything it could put together.' });
        finish(r, { tidy: false });
      } else if (how.kind === 'steer' && alive(r)) {
        useDj.setState({ notice: 'The DJ has nothing more to put together for that.' });
      }
      return false;
    }
    const { entries } = await renderSet(r, block, { first: false, cut: how.kind === 'steer' });
    // Stopped, or a newer steer came in while the voice was rendering: this set is out of date. Its clip, if any, is freed
    // at finish (never queued); the planner already counts its songs as used, which only costs the pool a few songs.
    if (!alive(r) || mine !== r.steers || !entries.length) return false;
    let ids: string[];
    if (how.kind === 'steer') ids = djPlayer.replaceUpcoming(r.ledger.entryIds(), entries, { cut: how.cut }).entryIds;
    else ids = djPlayer.append(entries);
    r.ledger.record(entries, ids);
    entries.forEach((e, i) => ids[i] && r.entryTrack.set(ids[i]!, r.byId.get(e.track.videoId) ?? djTrack({ id: e.track.videoId, title: e.track.title, artist: 'Auden DJ' })));
    r.previous = block;
    useDj.setState({ theme: theme(block) });
    return true;
  } catch (error) {
    console.error('DJ: could not plan the next set', error);
    return false;
  } finally {
    if (how.kind === 'append') r.planning = false;
  }
}

function onEntryStart(r: Run, entryId: string): void {
  if (!alive(r)) return;
  const queued = r.ledger.get(entryId);
  if (!queued) {
    // A song the listener queued themselves: no voice is playing, so the ring settles.
    useDj.setState({ voice: null });
    return;
  }
  if (queued.clipId) {
    const clip = clipEnvelopes.get(queued.clipId);
    const line = r.captions.get(queued.setIndex) ?? null;
    useDj.setState({ line, wordsOnly: false, voice: clip ? { envelope: clip.envelope, durationMs: clip.durationMs, clipEntryId: entryId } : null });
  } else {
    const track = r.entryTrack.get(entryId);
    if (track) djHistory.record(track.id);
    // A set with no clip shows its words when it starts; with a clip the words went with the voice.
    const caption = r.captions.get(queued.setIndex);
    const hadClip = r.ledger.clipEntryIds().some((id) => r.ledger.get(id)?.setIndex === queued.setIndex);
    useDj.setState({ voice: null, ...(caption && !hadClip ? { line: caption, wordsOnly: true } : {}) });
    // The set's voice has played: its loudness shape is not needed again.
    for (const id of r.ledger.clipEntryIds()) {
      const q = r.ledger.get(id);
      if (q?.clipId && q.setIndex === queued.setIndex) clipEnvelopes.delete(q.clipId);
    }
    if (caption && !hadClip) r.captions.delete(queued.setIndex);
  }
  const due = planNextDue(entryId, r.ledger);
  if (due !== null) {
    r.ledger.markPlanned(due);
    void planAndQueue(r, { kind: 'append' });
  }
}

function finish(r: Run, opts: { tidy: boolean }): void {
  if (run !== r) return;
  run = null;
  r.stopListening.forEach((off) => off());
  if (opts.tidy) djPlayer.remove(r.ledger.clipEntryIds());
  djPlayer.restore();
  // A clip that was rendered but never reached the queue is nobody's to free but ours.
  unqueuedClips(r.registered, r.ledger.queuedClipIds()).forEach((id) => {
    releaseClip(id);
    clipEnvelopes.delete(id);
  });
  useDj.setState({ phase: 'idle', voice: null, theme: null, pending: false, request: null });
}

export type DjStartResult = { ok: true } | { ok: false; reason: 'party' | 'not_enough_music' | 'failed' | 'busy' };

/** Starts the DJ: a surprise, or what [text] asks for. If it is already on, a new ask steers it (and an empty one is a new surprise). */
export async function startDj(loadPool: DjDeps['loadPool'], text = '', deps: DjDeps = defaultDeps(loadPool)): Promise<DjStartResult> {
  // One start at a time: an ask made while the DJ is still starting is not queued up behind it.
  if (useDj.getState().phase === 'starting') return { ok: false, reason: 'busy' };
  if (run) return steerDj(text);
  if (useParty.getState().code) {
    useDj.setState({ notice: 'The DJ can’t play in a Listen Together party: everyone hears the same YouTube stream.' });
    return { ok: false, reason: 'party' };
  }
  const token = ++tokenSeq;
  // If the listener plays something themselves while the DJ is still loading or rendering its opening, the DJ gives way.
  const epoch = djPlayer.contextEpoch();
  useDj.setState({ phase: 'starting', line: null, voice: null, notice: null, theme: null, pending: true, request: text.trim() || null });
  try {
    const pool = await deps.loadPool();
    if (djPlayer.contextEpoch() !== epoch) {
      useDj.setState({ phase: 'idle', pending: false, request: null });
      return { ok: false, reason: 'busy' };
    }
    if (pool.tracks.length < MIN_BLOCK) {
      useDj.setState({ phase: 'idle', pending: false, request: null, notice: 'Add some music first: the DJ needs at least a few songs to play.' });
      return { ok: false, reason: 'not_enough_music' };
    }
    const settings = useDjSettings.getState();
    const request = text.trim() ? parseRequest(text, pool.tracks) : null;
    const seed = deps.now();
    const month = (artist: string) => pool.tracks.filter((t) => leadArtist(t).toLowerCase() === artist.toLowerCase());
    const r: Run = {
      token,
      pool,
      byId: new Map(pool.tracks.map((t) => [t.id, t] as const)),
      planner: new DjPlanner({ pool: pool.tracks, now: deps.now(), seed, blockSize: settings.blockSize, request, recently: djHistory.recent() }),
      writer: new DjWriter({
        phrases: phrasesFor(settings.language),
        language: settings.language,
        seed,
        hour: new Date(deps.now()).getHours(),
        nowMs: deps.now(),
        artistPlays: (artist) => {
          const mine = month(artist);
          return mine.length ? { month: mine.reduce((n, t) => n + t.plays30, 0), ever: mine.reduce((n, t) => n + t.plays, 0) } : null;
        },
      }),
      ledger: new DjLedger(),
      registered: new Set(),
      previous: null,
      nextSet: 0,
      spokenInARow: INITIAL_SPOKEN_IN_A_ROW,
      entryTrack: new Map(),
      captions: new Map(),
      deps,
      language: settings.language,
      stopListening: [],
      planning: false,
      steers: 0,
    };
    run = r;
    const first = r.planner.next();
    if (!first) {
      run = null;
      useDj.setState({ phase: 'idle', pending: false, request: null, notice: 'The DJ couldn’t put a set together from your music.' });
      return { ok: false, reason: 'not_enough_music' };
    }
    const { entries, line } = await renderSet(r, first, { first: true, cut: true });
    if (run !== r) return { ok: false, reason: 'busy' };
    if (djPlayer.contextEpoch() !== epoch) {
      finish(r, { tidy: false });
      return { ok: false, reason: 'busy' };
    }
    if (!entries.length) {
      run = null;
      useDj.setState({ phase: 'idle', pending: false, request: null });
      return { ok: false, reason: 'failed' };
    }
    r.previous = first;
    r.stopListening.push(
      djPlayer.onEntryStart((id) => onEntryStart(r, id)),
      djPlayer.onBeforeNewContext(() => finish(r, { tidy: false })),
      // Hosting or joining a party ends the session: a party plays one YouTube stream for everyone.
      useParty.subscribe((s) => {
        if (s.code) finish(r, { tidy: true });
      }),
    );
    if (!djPlayer.start(entries)) {
      finish(r, { tidy: false });
      return { ok: false, reason: 'party' };
    }
    const ids = djPlayer.entryIdsOf(entries);
    r.ledger.record(entries, ids);
    entries.forEach((e, i) => ids[i] && r.entryTrack.set(ids[i]!, r.byId.get(e.track.videoId) ?? djTrack({ id: e.track.videoId, title: e.track.title, artist: 'Auden DJ' })));
    useDj.setState({ phase: 'on', pending: false, request: null, line, wordsOnly: !!line && !entries.some((e) => e.clipId), theme: theme(first) });
    void line;
    return { ok: true };
  } catch (error) {
    console.error('DJ: could not start', error);
    if (run && run.token === token) run = null;
    useDj.setState({ phase: 'idle', pending: false, request: null, notice: 'The DJ couldn’t start. Try again.' });
    return { ok: false, reason: 'failed' };
  }
}

/** A new ask while the DJ is on: parse it against the pool, and cut to a set that follows it (or a fresh surprise). */
export async function steerDj(text: string): Promise<DjStartResult> {
  const r = run;
  if (!r) return { ok: false, reason: 'failed' };
  const request = text.trim() ? parseRequest(text, r.pool.tracks) : null;
  r.planner.setRequest(request);
  // The DJ's screen is up while it chooses (and renders its voice), until the cut to the new set or the failure.
  useDj.setState({ pending: true, request: text.trim() || null });
  try {
    let ok = await planAndQueue(r, { kind: 'steer', cut: true });
    if (alive(r) && request && (!understood(request) || r.planner.relaxed) && !ok) ok = await searchSet(r, text);
    return ok ? { ok: true } : { ok: false, reason: 'failed' };
  } finally {
    if (run === r) useDj.setState({ pending: false, request: null });
  }
}

/**
 * An ask the library cannot meet: look for it on YouTube Music, bent towards the listener's genres and artists
 * (DjTaste), and make a set of what comes back. Needs the extension (the search goes through it); without it, nothing.
 */
export async function searchSet(r: Run, text: string): Promise<boolean> {
  try {
    const prof = tasteProfile(r.pool.tracks, () => []);
    const qs = tasteQueries(text, prof, namesGenre(text));
    const found = new Map<string, Track>();
    const results: Array<readonly [Query, readonly Hit[]]> = await Promise.all(
      qs.map(async (q) => {
        const tracks = await r.deps.search(q.text).catch(() => [] as Track[]);
        tracks.forEach((t) => found.set(t.videoId, t));
        return [q, tracks.map((t) => ({ id: t.videoId, artist: t.artists.map((a) => a.name).join(', ') }))] as const;
      }),
    );
    const known = new Set(r.pool.tracks.map((t) => leadArtist(t).toLowerCase()));
    const ids = mergeTaste(results, known, 20);
    const songs = ids.flatMap((id) => (found.get(id) ? [found.get(id)!] : []));
    if (songs.length < MIN_BLOCK) return false;
    const djTracks = songs.map((t) => djTrack({ id: t.videoId, title: t.title, artist: t.artists.map((a) => a.name).join(', '), discovery: true }));
    djTracks.forEach((d, i) => {
      r.byId.set(d.id, d);
      r.pool.playable.set(d.id, songs[i]!);
    });
    const block: DjBlock = { theme: { type: 'request', text, taste: null }, tracks: djTracks };
    const { entries } = await renderSet(r, block, { first: false, cut: true });
    if (!alive(r) || !entries.length) return false;
    const ids2 = djPlayer.replaceUpcoming(r.ledger.entryIds(), entries, { cut: true }).entryIds;
    r.ledger.record(entries, ids2);
    r.previous = block;
    return true;
  } catch (error) {
    console.error('DJ: the search failed', error);
    return false;
  }
}

/** "More like this": the song now playing is the kind of thing to lean toward. */
export function moreOfThis(): void {
  const r = run;
  const id = djPlayer.playingEntryId();
  const track = id ? r?.entryTrack.get(id) : null;
  if (r && track && !r.ledger.isClip(id!)) r.planner.more(track);
}

/** "Less of this": lean away from it, and move on. */
export function lessOfThis(next: () => void): void {
  const r = run;
  const id = djPlayer.playingEntryId();
  const track = id ? r?.entryTrack.get(id) : null;
  if (r && track && !r.ledger.isClip(id!)) {
    r.planner.less(track);
    next();
  }
}

/** Ends the session: the DJ's clips leave the queue, the songs stay, and shuffle, repeat and AutoPlay come back. */
export function stopDj(): void {
  if (run) finish(run, { tidy: true });
}

export const isDjOn = (): boolean => run !== null;

/** Test seam. */
export function resetDjForTests(): void {
  if (run) finish(run, { tidy: false });
  run = null;
  clipEnvelopes.clear();
  useDj.setState({ phase: 'idle', line: null, voice: null, notice: null, theme: null, pending: false, request: null });
}
