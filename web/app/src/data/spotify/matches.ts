import type { SpotifyTrack } from '@bitchord/shared/bridge';
import { best } from '@/data/match/trackMatcher';
import { searchSongs } from '@/data/music/api';
import type { Track } from '@/data/music/types';
import { notifyStores } from '@/auden/db/notify';
import { openAudenDb, type SpotifyMatchRow } from '@/auden/db/schema';
import { candidateOf } from './api';
import { storedJsonToTrack, trackToStoredJson } from './songJson';

/**
 * Which YouTube Music song a Spotify track is, remembered across sessions (SpotifyMatches.kt, over the
 * `spotify_match` store). A song found is kept for good; one not found is looked for again after three
 * days; one the listener picked by hand is never replaced by a search. The store is global, not per
 * account: a Spotify track id is the catalogue's, the same for everyone.
 */

export const MatchStatus = { AUTO: 'AUTO', MANUAL: 'MANUAL', MISSING: 'MISSING' } as const;

/** How long a "not found" stands before the song is searched for again (SpotifyMatches.RETRY_MISSING_MS). */
export const RETRY_MISSING_MS = 3 * 24 * 60 * 60 * 1000;

export type MatchResult =
  | { status: 'found'; track: Track; manual: boolean }
  | { status: 'missing' }
  /** The search itself failed (offline, a throttle): nothing is remembered, so the next open asks again. */
  | { status: 'error'; error: unknown };

export interface MatchDeps {
  search: (query: string) => Promise<Track[]>;
  now: () => number;
}

const defaultDeps: MatchDeps = { search: searchSongs, now: Date.now };

const durationSecOf = (t: SpotifyTrack) => (t.durationMs > 0 ? Math.round(t.durationMs / 1000) : null);

/** A remembered row, read the way Android does: a row with a song is a found one, whatever its status. */
function fromRow(row: SpotifyMatchRow | undefined, now: number): MatchResult | null {
  if (!row) return null;
  const track = row.songJson ? storedJsonToTrack(row.songJson) : null;
  if (track) return { status: 'found', track, manual: row.status === MatchStatus.MANUAL };
  if (row.status === MatchStatus.MISSING && now - row.matchedAt < RETRY_MISSING_MS) return { status: 'missing' };
  return null;
}

/** Whoever is told when the listener picks a match by hand, to apply it to rows already on screen. */
type ManualListener = (spotifyId: string, track: Track) => void;
const manualListeners = new Set<ManualListener>();
export function onManualMatch(listener: ManualListener): () => void {
  manualListeners.add(listener);
  return () => void manualListeners.delete(listener);
}

/**
 * Writes an automatic result in ONE transaction that first looks: a hand-picked match that landed while
 * the search was in flight must win, so what is returned is what is stored.
 */
async function writeAuto(spotifyId: string, row: SpotifyMatchRow, now: number): Promise<MatchResult> {
  const db = await openAudenDb();
  const tx = db.transaction('spotify_match', 'readwrite');
  const existing = await tx.store.get(spotifyId);
  const manual = fromRow(existing, now);
  if (existing?.status === MatchStatus.MANUAL && manual) {
    await tx.done;
    return manual;
  }
  await tx.store.put(row);
  await tx.done;
  notifyStores('spotify_match');
  return row.songJson ? { status: 'found', track: storedJsonToTrack(row.songJson)!, manual: false } : { status: 'missing' };
}

/**
 * The YouTube Music song for [spotify]: the remembered one, else the best search candidate by title,
 * artist, length and album, else the search's first hit (SpotifyImporter.matchTrack).
 */
export async function resolveSpotifyTrack(spotify: SpotifyTrack, deps: MatchDeps = defaultDeps): Promise<MatchResult> {
  const now = deps.now();
  try {
    const db = await openAudenDb();
    const remembered = fromRow(await db.get('spotify_match', spotify.id), now);
    if (remembered) return remembered;
  } catch {
    // A database that cannot be read must not stop the song being matched: search as if nothing were kept.
  }
  let candidates: Track[];
  try {
    candidates = await deps.search([spotify.title, spotify.artist].filter((s) => s.trim()).join(' '));
  } catch (error) {
    return { status: 'error', error };
  }
  const target = { title: spotify.title, artist: spotify.artist, durationSec: durationSecOf(spotify), album: spotify.album };
  const scored = best(candidates.map((c) => ({ ...candidateOf(c), track: c })), target);
  const hit = scored?.track ?? candidates[0] ?? null;
  const found = hit ? { ...hit, thumbnail: hit.thumbnail ?? spotify.imageUrl } : null;
  try {
    return await writeAuto(
      spotify.id,
      { spotifyId: spotify.id, status: found ? MatchStatus.AUTO : MatchStatus.MISSING, songJson: found ? trackToStoredJson(found) : null, matchedAt: now },
      now,
    );
  } catch {
    // Not remembered, but still the answer.
    return found ? { status: 'found', track: found, manual: false } : { status: 'missing' };
  }
}

/** The listener's own pick: written whatever is there, and never replaced by a search afterwards. */
export async function setManualMatch(spotifyId: string, track: Track): Promise<void> {
  const db = await openAudenDb();
  await db.put('spotify_match', { spotifyId, status: MatchStatus.MANUAL, songJson: trackToStoredJson(track), matchedAt: Date.now() });
  notifyStores('spotify_match');
  manualListeners.forEach((l) => l(spotifyId, track));
}
