import { withBackgroundVocals, type LyricLine } from './model';
import { lyricsGet } from './http';
import type { Parsed } from './parse';
import * as providers from './providers';
import { enabledSources, useLyricsSettings } from './settings';
import { artistForLyricsSearch, forLyricsSearch, sourceById, type LyricsSourceId } from './sources';
import { writeTtml } from './ttml';

/**
 * Where the player gets its lyrics: a port of LyricsRepository.kt.
 *
 * - Every enabled source is asked at the same time, but their answers are
 *   taken in priority order: a lower source finishing first never preempts a
 *   higher one still pending.
 * - A word-timed answer wins outright. Otherwise the highest-priority timed
 *   answer is taken, unless "prefer syllable timing" is on, in which case
 *   the search carries on for a word-timed one and the line-timed answer is
 *   kept as a fallback.
 * - Before anyone is asked, BiniLyrics names the recording (an ISRC, in at
 *   most 2.5 s), so lrc.red and LyricsPlus can ask by recording, not name.
 * - Genius (a scraper) is only asked once every source above it has missed.
 */

export interface LyricsTrack {
  videoId: string;
  title: string;
  artist: string;
  durationMs: number;
  album: string | null;
}

export interface LyricsResult {
  source: LyricsSourceId;
  label: string;
  lines: LyricLine[];
  /** What am-lyrics renders: the provider's own TTML, or ours written from the lines. */
  ttml: string;
  wordSynced: boolean;
  synced: boolean;
}

/** One provider's part in a lookup, for the provider picker. */
export type SourceState = 'pending' | 'found' | 'missing';

const IDENTIFY_TIMEOUT_MS = 2500;
const CACHE_TTL_MS = 12 * 60 * 60_000;

/** The recording behind a video id, once something worked it out (bounded, memory only). */
const isrcs = new Map<string, string>();
function remember(videoId: string, isrc: string | null | undefined) {
  if (!isrc || !videoId) return;
  isrcs.delete(videoId);
  isrcs.set(videoId, isrc);
  if (isrcs.size > 100) isrcs.delete(isrcs.keys().next().value!);
}

const cache = new Map<string, { at: number; result: LyricsResult | null }>();

/** Drops every remembered answer: a newly allowed host may have what was missing. */
export function forgetLyrics() {
  cache.clear();
}

function toResult(source: LyricsSourceId, parsed: Parsed): LyricsResult {
  const lines = withBackgroundVocals(parsed.lines);
  return {
    source,
    label: sourceById(source)?.label ?? source,
    lines,
    ttml: parsed.ttml ?? writeTtml(lines),
    wordSynced: lines.some((l) => l.words.length),
    synced: lines.some((l) => l.timeMs > 0 || l.words.length),
  };
}

function withTimeout<T>(promise: Promise<T>, ms: number): Promise<T | null> {
  return Promise.race([promise, new Promise<null>((resolve) => setTimeout(() => resolve(null), ms))]);
}

export interface LookupOptions {
  /** Ask this one provider only: the panel's provider picker. */
  only?: LyricsSourceId;
  onSource?: (source: LyricsSourceId, state: SourceState) => void;
  /**
   * Stop waiting for slower providers after this long and answer with the best found so far (a
   * line-synced or plain result beats losing everything to one hung mirror). The batch uses it.
   */
  settleAfterMs?: number;
  /** Ask these providers, in this order, instead of the current settings (a batch snapshots them at its start). */
  sequence?: readonly LyricsSourceId[];
  /** Ignore a remembered answer: a cached "nothing found" is not what a batch should report. */
  fresh?: boolean;
}

export async function lookupLyrics(
  track: LyricsTrack,
  { only, onSource, settleAfterMs, fresh, sequence: given }: LookupOptions = {},
): Promise<LyricsResult | null> {
  const sequence = only ? [only] : [...(given ?? enabledSources())];
  if (!sequence.length) return null;
  const { paxsenixKey, preferSyllables } = useLyricsSettings.getState();
  // preferSyllables picks a different winner from the same answers, so it is part of the key.
  const key = `${track.videoId}|${track.durationMs}|${track.title}|${track.artist}|${only ?? ''}|${sequence.join(',')}|${preferSyllables}`;
  const cached = cache.get(key);
  if (!fresh && cached && Date.now() - cached.at < CACHE_TTL_MS) return cached.result;

  const query: providers.LyricsQuery = {
    videoId: track.videoId,
    title: forLyricsSearch(track.title),
    artist: artistForLyricsSearch(track.artist),
    durationMs: track.durationMs,
    album: track.album,
    isrc: isrcs.get(track.videoId) ?? null,
  };
  let hit: providers.BiniHit | null = null;
  if (!query.isrc && sequence.includes('bini')) {
    hit = await withTimeout(providers.biniIdentify(query).catch(() => null), IDENTIFY_TIMEOUT_MS);
    if (hit?.isrc) {
      remember(track.videoId, hit.isrc);
      query.isrc = hit.isrc;
    }
  }

  const documents = new Map<string, Promise<string | null>>();
  const get: providers.SharedGet = (url) => {
    let pending = documents.get(url);
    if (!pending) {
      pending = lyricsGet(url);
      documents.set(url, pending);
    }
    return pending;
  };

  const fetchFrom = (source: LyricsSourceId): Promise<Parsed | null> => {
    switch (source) {
      case 'lrc-red':
        return providers.lrcRed(query, get);
      case 'bini':
        return providers.bini(query, get, hit).then((found) => {
          remember(track.videoId, found?.isrc);
          return found;
        });
      case 'better-lyrics':
        return providers.betterLyrics(query);
      case 'better-lyrics-portato':
        return providers.betterLyricsPortato(query);
      case 'paxsenix':
        return providers.paxSenix(query);
      case 'paxsenix-spotify':
        return providers.paxSenixSpotify(query, paxsenixKey);
      case 'paxsenix-musixmatch':
        return providers.paxSenixMusixmatch(query, paxsenixKey);
      case 'lyrics-plus':
        return providers.lyricsPlus(query);
      case 'unison':
        return providers.unison(query);
      case 'youtube-captions':
        return providers.youtubeCaptions(query);
      case 'youtube-music':
        return providers.youtubeMusicLyrics(query);
      case 'kugou':
        return providers.kugou(query);
      case 'lrclib':
        return providers.lrclib(query);
      case 'musixmatch':
        return providers.musixmatch(query);
      case 'genius':
        return providers.genius(query);
    }
  };

  /** Every answer that has arrived, for settling early on what is already in hand. */
  const finished = new Map<LyricsSourceId, LyricsResult | null>();
  const run = (source: LyricsSourceId) => {
    onSource?.(source, 'pending');
    return fetchFrom(source)
      .catch(() => null)
      .then((parsed) => {
        const result = parsed?.lines.some((l) => l.text.trim()) ? toResult(source, parsed) : null;
        finished.set(source, result);
        onSource?.(source, result ? 'found' : 'missing');
        return result;
      });
  };

  // Genius starts lazily: only if it's reached.
  const racing = sequence.map((source) => ({ source, job: source === 'genius' ? null : run(source) }));
  let lineSynced: LyricsResult | null = null;
  let plain: LyricsResult | null = null;
  let winner: LyricsResult | null = null;
  const timedOut = Symbol('settle');
  let settledEarly = false;
  const deadline =
    settleAfterMs !== undefined ? new Promise<typeof timedOut>((resolve) => setTimeout(() => resolve(timedOut), settleAfterMs)) : null;
  for (const entry of racing) {
    if (entry.source === 'genius' && (lineSynced || plain)) continue;
    const pending = entry.job ?? run(entry.source);
    const answer = deadline ? await Promise.race([pending, deadline]) : await pending;
    if (answer === timedOut) {
      // One slow provider must not cost the answers that already arrived: best of those, in order.
      const have = sequence.map((source) => finished.get(source)).filter((r): r is LyricsResult => !!r);
      winner = have.find((r) => r.wordSynced) ?? have.find((r) => r.synced) ?? have[0] ?? null;
      settledEarly = true;
      break;
    }
    const found = answer;
    if (!found) continue;
    if (found.wordSynced) {
      winner = found;
      break;
    }
    if (found.synced && !preferSyllables) {
      winner = found;
      break;
    }
    if (found.synced) lineSynced ??= found;
    else plain ??= found;
  }
  winner ??= lineSynced ?? plain;
  // An answer cut short by the deadline is not the final word: the panel, with no deadline, may do better.
  if (!settledEarly) cache.set(key, { at: Date.now(), result: winner });
  return winner;
}
