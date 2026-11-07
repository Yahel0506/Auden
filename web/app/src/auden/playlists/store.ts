import { useMemo } from 'react';
import { useQuery } from '@tanstack/react-query';
import type { IDBPTransaction } from 'idb';
import type { Track } from '@/data/music/types';
import { CLIP_ID_PREFIX, isSyntheticId, localRowIdOf } from '@/playback/source';
import { newTrack } from '../db/dao';
import { notifyStores } from '../db/notify';
import { requestPersistence } from '../db/persist';
import { audenKey } from '../db/queries';
import {
  openAudenDb,
  TrackOrigin,
  type AudenDBSchema,
  type LocalArtRow,
  type PlaylistEntryRow,
  type PlaylistRow,
  type StoreName,
  type TrackRow,
} from '../db/schema';
import { splitArtists } from '../library/artistSplit';
import { parseDurationText, sameSong, type IdentitySong } from '../library/identity';
import { artKeyFromUrl, artUrlOf } from '../library/scan/diff';
import { trackOfRow, useLocalArt } from '../library/ui/data';
import { reconcile } from './reconcile';

export { reconcile } from './reconcile';
export type { ReconcileEntry, ReconcileInput, ReconcileMatch, ReconcileResult } from './reconcile';

/**
 * Playlists on this device (LocalPlaylists.kt + CloudSaver.kt over AudenDb). Every operation is one IndexedDB
 * transaction over the playlist, its entries, the track rows they point at and the playlist's cover, so another tab
 * (or a second click) sees either all of it or none. Entries are addressed by `entryId`, never by index: an id
 * survives moves and removals, so a list rendered a moment ago cannot move or delete the wrong song.
 *
 * Positions are kept dense by the operations that reorder; elsewhere (a file that left the library takes its
 * entries with it, scan/apply.ts) they may have gaps, which only the order matters for.
 */

export const PLAYLIST_ID_PREFIX = 'local:playlist:';
export const isLocalPlaylistId = (id: string): boolean => id.startsWith(PLAYLIST_ID_PREFIX);

/** The `local_art` key of a playlist's own cover (a mosaic): `playlist:<playlistId>:<tag>`. */
export const playlistArtKey = (playlistId: string, tag: string): string => `playlist:${playlistId}:${tag}`;
export const playlistArtUrl = (playlistId: string, tag: string): string => artUrlOf(playlistArtKey(playlistId, tag));

/** A track that cannot be stored in a playlist: a Spotify row with no YouTube match, a DJ clip, a vanished file. */
export class RefusedTrackError extends Error {
  constructor(message: string) {
    super(message);
    this.name = 'RefusedTrackError';
  }
}

/** A save with nothing to save: refused, so a failed read or match never empties a copy. */
export class EmptySourceError extends Error {
  constructor() {
    super('nothing to save');
    this.name = 'EmptySourceError';
  }
}

export type PlaylistEntry = PlaylistEntryRow & { entryId: number };

export interface PlaylistItem {
  entryId: number;
  entry: PlaylistEntry;
  /** Null only if the row went without its entries (should not happen; shown as unavailable). */
  row: TrackRow | null;
  /** Ready for the player; null when the row has neither a YouTube id nor a file. */
  track: Track | null;
}

export interface PlaylistSummary {
  playlist: PlaylistRow;
  count: number;
  /** Up to four distinct stored thumbnails of the first songs (may be `auden-art://` keys: map them with useLocalArt). */
  thumbnails: string[];
}

// ---- Transactions -------------------------------------------------------------------------------------------

type Stores = ('playlist' | 'playlist_entry' | 'track' | 'local_art')[];
const STORES: Stores = ['playlist', 'playlist_entry', 'track', 'local_art'];
type Tx<M extends IDBTransactionMode = IDBTransactionMode> = IDBPTransaction<AudenDBSchema, Stores, M>;
type WTx = Tx<'readwrite'>;

/** One readwrite transaction. A throw aborts it, so nothing half-done is committed. */
async function write<T>(fn: (tx: WTx) => Promise<T>): Promise<T> {
  const db = await openAudenDb();
  const tx = db.transaction(STORES, 'readwrite');
  try {
    const result = await fn(tx);
    await tx.done;
    return result;
  } catch (error) {
    try {
      tx.abort();
    } catch {
      // Already finished or aborting.
    }
    tx.done.catch(() => {});
    throw error;
  }
}

async function read<T>(fn: (tx: Tx<'readonly'>) => Promise<T>): Promise<T> {
  const db = await openAudenDb();
  const tx = db.transaction(STORES, 'readonly');
  const result = await fn(tx);
  await tx.done;
  return result;
}

const entryRange = (playlistId: string) => IDBKeyRange.bound([playlistId, -Infinity], [playlistId, Infinity]);

/** The playlist's entries in order (position, then insertion). */
async function entriesIn<M extends IDBTransactionMode>(tx: Tx<M>, playlistId: string): Promise<PlaylistEntry[]> {
  return (await tx.objectStore('playlist_entry').index('playlist_position').getAll(entryRange(playlistId))) as PlaylistEntry[];
}

async function rowsOf<M extends IDBTransactionMode>(tx: Tx<M>, trackIds: Iterable<number>): Promise<Map<number, TrackRow>> {
  const store = tx.objectStore('track');
  const out = new Map<number, TrackRow>();
  for (const id of new Set(trackIds)) {
    const row = await store.get(id);
    if (row) out.set(id, row);
  }
  return out;
}

/** Rewrites positions 0..n-1 in list order, touching only entries whose position changes. */
async function renumber(tx: WTx, list: readonly PlaylistEntry[]): Promise<void> {
  const store = tx.objectStore('playlist_entry');
  for (let i = 0; i < list.length; i++) {
    const e = list[i]!;
    if (e.position !== i) await store.put({ ...e, position: i });
  }
}

async function touch(tx: WTx, playlistId: string, patch: Partial<PlaylistRow> = {}): Promise<PlaylistRow | undefined> {
  const store = tx.objectStore('playlist');
  const current = await store.get(playlistId);
  if (!current) return undefined;
  const next = { ...current, ...patch, updatedAt: Date.now() };
  await store.put(next);
  return next;
}

/** A cover URL safe to keep: http(s) or one of ours. Never a blob:/data: URL (they die with the page or bloat the row). */
function storableUrl(url: string | null | undefined): string | null {
  const u = url?.trim();
  if (!u) return null;
  return /^https?:\/\//i.test(u) || artKeyFromUrl(u) ? u : null;
}

/**
 * Deletes the playlist cover behind [coverUrl] when it is a playlist's own `local_art` cover and no other playlist
 * shows it. Returns whether something was deleted.
 */
async function dropOwnedArt(tx: WTx, coverUrl: string | null, playlistId: string): Promise<boolean> {
  const key = artKeyFromUrl(coverUrl);
  if (!key?.startsWith('playlist:')) return false;
  const others = await tx.objectStore('playlist').getAll();
  if (others.some((p) => p.id !== playlistId && p.coverUrl === coverUrl)) return false;
  if (!(await tx.objectStore('local_art').get(key))) return false;
  await tx.objectStore('local_art').delete(key);
  return true;
}

// ---- Track identity -----------------------------------------------------------------------------------------

const songOfRow = (row: TrackRow): IdentitySong => ({
  videoId: row.videoId,
  localUri: row.localUri,
  title: row.title,
  artist: row.artist,
  durationSec: row.durationMs ? row.durationMs / 1000 : parseDurationText(row.durationText),
});

const artistOf = (track: Track): string => track.artists.map((a) => a.name).filter(Boolean).join(', ');

interface Candidate {
  /** The row this song already has. */
  rowId?: number;
  /** The row to insert when it has none. */
  insert?: TrackRow;
  song: IdentitySong;
  /** Equal keys: the same row (or the same not-yet-stored video). */
  key: string;
}

/** Which row a track or row id is (or would be). Throws RefusedTrackError for what a playlist cannot hold. */
async function candidateOf<M extends IDBTransactionMode>(tx: Tx<M>, input: Track | number): Promise<Candidate> {
  const store = tx.objectStore('track');
  const existingRow = async (id: number): Promise<Candidate> => {
    const row = Number.isSafeInteger(id) ? await store.get(id) : undefined;
    if (!row) throw new RefusedTrackError(`no library row ${id}`);
    return { rowId: id, song: songOfRow(row), key: `row:${id}` };
  };
  if (typeof input === 'number') return existingRow(input);

  const track = input;
  const local = localRowIdOf(track.videoId);
  if (local !== null) return existingRow(Number(local));
  if (track.source === 'clip' || track.source === 'local' || track.videoId.startsWith(CLIP_ID_PREFIX)) {
    throw new RefusedTrackError('a clip or a malformed local track');
  }
  if (track.unavailable || track.videoId.startsWith('spotify:') || isSyntheticId(track.videoId) || !track.videoId.trim()) {
    throw new RefusedTrackError('not a YouTube song');
  }
  const videoId = track.videoId;
  // Prefer the row that has the file: the library and the playlist then agree on one song.
  const rows = (await store.index('videoId').getAll(videoId)).filter((r) => r.id != null);
  const row = rows.find((r) => r.localUri) ?? rows[0];
  if (row) return { rowId: row.id!, song: songOfRow(row), key: `row:${row.id}` };
  const insert = newTrack({
    videoId,
    title: track.title,
    artist: artistOf(track),
    artistId: track.artists[0]?.id ?? null,
    album: track.album?.name ?? null,
    albumId: track.album?.id ?? null,
    thumbnailUrl: storableUrl(track.thumbnail),
    durationMs: track.durationSec ? Math.round(track.durationSec * 1000) : null,
    origin: TrackOrigin.YTM,
  });
  return { insert, song: songOfRow(insert), key: `video:${videoId}` };
}

/** The candidate's row id, inserting its row when it has none (and remembering it for later duplicates in the batch). */
async function rowIdOf(tx: WTx, c: Candidate, inserted: Map<string, number>): Promise<number> {
  if (c.rowId != null) return c.rowId;
  const known = inserted.get(c.key);
  if (known != null) return known;
  const id = await tx.objectStore('track').add(c.insert!);
  inserted.set(c.key, id);
  return id;
}

async function tryCandidate<M extends IDBTransactionMode>(tx: Tx<M>, input: Track | number): Promise<Candidate | null> {
  try {
    return await candidateOf(tx, input);
  } catch (error) {
    if (error instanceof RefusedTrackError) return null;
    throw error;
  }
}

/**
 * The library row for a track, created when a YouTube song has none: a local track is its own row; a YouTube id maps
 * to the row that has it (the one with a file first). Spotify rows and clips are refused (RefusedTrackError).
 */
export async function ensureTrackRow(track: Track): Promise<number> {
  let added = false;
  const id = await write(async (tx) => {
    const c = await candidateOf(tx, track);
    added = c.rowId == null;
    return rowIdOf(tx, c, new Map());
  });
  if (added) notifyStores('track');
  return id;
}

// ---- Writes ------------------------------------------------------------------------------------------------

export interface CreateOptions {
  sourceId?: string | null;
  coverUrl?: string | null;
  /** Aligned with the tracks: the Spotify id each one was matched for (Spotify copies). */
  fromSpotifyIds?: ReadonlyArray<string | undefined>;
}

export interface AddResult {
  added: number;
  /** Already in the playlist (or twice in the input). */
  duplicates: number;
  /** Could not be stored (Spotify rows without a match, clips, vanished files). */
  skipped: number;
}

/** Writes a new playlist with its first songs (deduplicated by row / video id). */
async function insertPlaylist(
  tx: WTx,
  title: string,
  candidates: ReadonlyArray<Candidate | null>,
  opts: CreateOptions,
  inserted: Map<string, number>,
): Promise<{ row: PlaylistRow; first: boolean } & AddResult> {
  const store = tx.objectStore('playlist');
  const first = (await store.count()) === 0;
  const now = Date.now();
  const row: PlaylistRow = {
    id: `${PLAYLIST_ID_PREFIX}${crypto.randomUUID()}`,
    title: title.trim() || 'New Playlist',
    createdAt: now,
    updatedAt: now,
    pinned: false,
    sourceId: opts.sourceId ?? null,
    coverUrl: storableUrl(opts.coverUrl),
  };
  await store.add(row);
  const seen = new Set<string>();
  let position = 0;
  let duplicates = 0;
  let skipped = 0;
  for (let i = 0; i < candidates.length; i++) {
    const c = candidates[i];
    if (!c) {
      skipped++;
      continue;
    }
    if (seen.has(c.key)) {
      duplicates++;
      continue;
    }
    seen.add(c.key);
    const trackId = await rowIdOf(tx, c, inserted);
    const fromSpotifyId = opts.fromSpotifyIds?.[i];
    await tx.objectStore('playlist_entry').add({ playlistId: row.id, trackId, position: position++, ...(fromSpotifyId ? { fromSpotifyId } : {}) });
  }
  return { row, first, added: position, duplicates, skipped };
}

function notifyWrite(inserted: Map<string, number>, ...stores: StoreName[]): void {
  notifyStores(...stores, ...(inserted.size ? (['track'] as const) : []));
}

/** A new playlist on this device. The first one asks the browser to keep the library (persistent storage). */
export async function createPlaylist(
  title: string,
  tracks: ReadonlyArray<Track | number> = [],
  opts: CreateOptions = {},
): Promise<{ id: string } & AddResult> {
  const inserted = new Map<string, number>();
  const result = await write(async (tx) => {
    const candidates: Array<Candidate | null> = [];
    for (const t of tracks) candidates.push(await tryCandidate(tx, t));
    return insertPlaylist(tx, title, candidates, opts, inserted);
  });
  notifyWrite(inserted, 'playlist', 'playlist_entry');
  if (result.first) void requestPersistence();
  return { id: result.row.id, added: result.added, duplicates: result.duplicates, skipped: result.skipped };
}

export async function renamePlaylist(id: string, title: string): Promise<boolean> {
  const clean = title.trim();
  if (!clean) return false;
  const done = await write(async (tx) => (await touch(tx, id, { title: clean })) !== undefined);
  if (done) notifyStores('playlist');
  return done;
}

/** Removes the playlist, its entries (not the tracks) and its own cover. */
export async function deletePlaylist(id: string): Promise<boolean> {
  let art = false;
  const done = await write(async (tx) => {
    const playlist = await tx.objectStore('playlist').get(id);
    const entries = tx.objectStore('playlist_entry');
    for (let c = await entries.index('playlist_position').openKeyCursor(entryRange(id)); c; c = await c.continue()) {
      await entries.delete(c.primaryKey);
    }
    if (!playlist) return false;
    art = await dropOwnedArt(tx, playlist.coverUrl, id);
    await tx.objectStore('playlist').delete(id);
    return true;
  });
  notifyStores('playlist', 'playlist_entry', ...(art ? (['local_art'] as const) : []));
  return done;
}

/** A plain copy ("<title> copy", made unique): same songs in the same order, not linked to any source. */
export async function duplicatePlaylist(id: string): Promise<string | null> {
  let art = false;
  const copyId = await write(async (tx) => {
    const store = tx.objectStore('playlist');
    const src = await store.get(id);
    if (!src) return null;
    const taken = new Set((await store.getAll()).map((p) => p.title.trim().toLowerCase()));
    const base = `${src.title} copy`;
    let title = base;
    for (let n = 2; taken.has(title.toLowerCase()); n++) title = `${base} ${n}`;
    const now = Date.now();
    const row: PlaylistRow = { id: `${PLAYLIST_ID_PREFIX}${crypto.randomUUID()}`, title, createdAt: now, updatedAt: now, pinned: false, sourceId: null, coverUrl: src.coverUrl };
    // A cover of the original's own is copied under the copy's key: deleting one must not take the other's.
    const key = artKeyFromUrl(src.coverUrl);
    if (key?.startsWith('playlist:')) {
      const bytes = await tx.objectStore('local_art').get(key);
      const ownPrefix = `playlist:${src.id}:`;
      if (bytes) {
        const copyKey = playlistArtKey(row.id, key.startsWith(ownPrefix) ? key.slice(ownPrefix.length) : 'copy');
        await tx.objectStore('local_art').put({ ...bytes, key: copyKey });
        row.coverUrl = artUrlOf(copyKey);
        art = true;
      } else {
        row.coverUrl = null;
      }
    }
    await store.add(row);
    const entries = await entriesIn(tx, id);
    for (let i = 0; i < entries.length; i++) {
      await tx.objectStore('playlist_entry').add({ playlistId: row.id, trackId: entries[i]!.trackId, position: i });
    }
    return row.id;
  });
  if (copyId) notifyStores('playlist', 'playlist_entry', ...(art ? (['local_art'] as const) : []));
  return copyId;
}

/**
 * Appends the songs the playlist does not hold yet: the same row, or the same song by id or metadata (sameSong).
 * A missing playlist adds nothing.
 */
export async function addTracks(
  playlistId: string,
  tracks: ReadonlyArray<Track | number>,
  opts: { fromSpotifyIds?: ReadonlyArray<string | undefined> } = {},
): Promise<AddResult> {
  const inserted = new Map<string, number>();
  const result = await write(async (tx): Promise<AddResult> => {
    if (!(await tx.objectStore('playlist').get(playlistId))) return { added: 0, duplicates: 0, skipped: 0 };
    const entries = await entriesIn(tx, playlistId);
    const rows = await rowsOf(tx, entries.map((e) => e.trackId));
    const held: Array<{ rowId?: number; key: string; song: IdentitySong }> = [];
    for (const e of entries) {
      const row = rows.get(e.trackId);
      if (row) held.push({ rowId: e.trackId, key: `row:${e.trackId}`, song: songOfRow(row) });
    }
    let position = entries.length ? entries[entries.length - 1]!.position + 1 : 0;
    const result: AddResult = { added: 0, duplicates: 0, skipped: 0 };
    for (let i = 0; i < tracks.length; i++) {
      const c = await tryCandidate(tx, tracks[i]!);
      if (!c) {
        result.skipped++;
        continue;
      }
      if (held.some((h) => h.key === c.key || sameSong(c.song, h.song))) {
        result.duplicates++;
        continue;
      }
      held.push(c);
      const trackId = await rowIdOf(tx, c, inserted);
      const fromSpotifyId = opts.fromSpotifyIds?.[i];
      await tx.objectStore('playlist_entry').add({ playlistId, trackId, position: position++, ...(fromSpotifyId ? { fromSpotifyId } : {}) });
      result.added++;
    }
    if (result.added) await touch(tx, playlistId);
    return result;
  });
  if (result.added) notifyWrite(inserted, 'playlist', 'playlist_entry');
  return result;
}

/** Removes one entry (the same song elsewhere in the list stays). A stale id is a no-op. */
export async function removeEntry(entryId: number): Promise<boolean> {
  const done = await write(async (tx) => {
    const store = tx.objectStore('playlist_entry');
    const entry = await store.get(entryId);
    if (!entry) return false;
    await store.delete(entryId);
    await touch(tx, entry.playlistId);
    return true;
  });
  if (done) notifyStores('playlist', 'playlist_entry');
  return done;
}

/**
 * Moves an entry before another one (null: to the end), and rewrites positions densely, in place: entry ids stay.
 * Built for drag-and-drop on a list that may be out of date: an entry that is gone, or not in this playlist, or a
 * target that is gone, changes nothing.
 */
export async function moveEntry(playlistId: string, entryId: number, beforeEntryId: number | null): Promise<boolean> {
  if (entryId === beforeEntryId) return false;
  const done = await write(async (tx) => {
    const list = await entriesIn(tx, playlistId);
    const from = list.findIndex((e) => e.entryId === entryId);
    if (from < 0) return false;
    if (beforeEntryId !== null && !list.some((e) => e.entryId === beforeEntryId)) return false;
    const [moving] = list.splice(from, 1);
    const to = beforeEntryId === null ? list.length : list.findIndex((e) => e.entryId === beforeEntryId);
    list.splice(to, 0, moving!);
    if (list.every((e, i) => e.position === i) && to === from) return false;
    await renumber(tx, list);
    await touch(tx, playlistId);
    return true;
  });
  if (done) notifyStores('playlist', 'playlist_entry');
  return done;
}

/**
 * Sets the playlist's cover, and its bytes when it is one of ours (a mosaic in `local_art`). The previous cover of
 * its own is deleted in the same transaction, so covers never pile up.
 */
export async function setPlaylistCover(playlistId: string, coverUrl: string | null, art?: LocalArtRow): Promise<boolean> {
  const url = storableUrl(coverUrl);
  const done = await write(async (tx) => {
    const current = await tx.objectStore('playlist').get(playlistId);
    if (!current) return false;
    if (art) await tx.objectStore('local_art').put(art);
    if (current.coverUrl !== url) {
      await touch(tx, playlistId, { coverUrl: url });
      await dropOwnedArt(tx, current.coverUrl, playlistId);
    }
    return true;
  });
  if (done) notifyStores('playlist', 'local_art');
  return done;
}

// ---- Saving a cloud playlist (sync) -------------------------------------------------------------------------

export interface SaveFromSourceInput {
  /** Android's ids: a YouTube browse id without "VL", or `spotify:playlist:<id>`. */
  sourceId: string;
  title: string;
  /** The playable songs, in the original's order (Spotify: the matched ones). Must not be empty. */
  tracks: readonly Track[];
  /** The original's cover; a copy takes the newest one it is given. */
  coverUrl?: string | null;
  /** Spotify: aligned with `tracks`, the Spotify id each was matched for. */
  spotifyIds?: ReadonlyArray<string | undefined>;
  /**
   * Spotify: every track id the original lists right now, matched or not. Without it (YouTube) a sync only adds.
   * Removal also needs `complete`.
   */
  listedSpotifyIds?: readonly string[] | null;
  /** Every page of the original was read. Default false: nothing is removed. */
  complete?: boolean;
}

export interface SaveResult {
  playlistId: string;
  created: boolean;
  added: number;
  removed: number;
  /** The copy's title (the listener may have renamed it; a sync keeps their name). */
  name: string;
}

function withSaveLock<T>(sourceId: string, fn: () => Promise<T>): Promise<T> {
  const locks = (typeof navigator !== 'undefined' ? navigator.locks : undefined) as LockManager | undefined;
  return locks ? (locks.request(`auden-save:${sourceId}`, fn) as Promise<T>) : fn();
}

/**
 * Saves a cloud playlist or album to the device, or brings the copy saved earlier up to date (CloudSaver.kt). Network
 * free: the caller has read the original and matched it. One transaction (under a per-source lock across tabs), so
 * two saves of one page make one copy. See reconcile.ts for what a sync may remove.
 */
export async function saveFromSource(input: SaveFromSourceInput): Promise<SaveResult> {
  if (!input.tracks.length) throw new EmptySourceError();
  const inserted = new Map<string, number>();
  let art = false;
  let first = false;
  const result = await withSaveLock(input.sourceId, () =>
    write(async (tx): Promise<SaveResult> => {
      const all = await tx.objectStore('playlist').getAll();
      const existing = all.filter((p) => p.sourceId === input.sourceId).sort((a, b) => a.createdAt - b.createdAt)[0];

      const matched: Array<{ spotifyId?: string; trackKey: string; c: Candidate }> = [];
      for (let i = 0; i < input.tracks.length; i++) {
        const c = await tryCandidate(tx, input.tracks[i]!);
        const spotifyId = input.spotifyIds?.[i];
        if (c) matched.push({ c, trackKey: c.key, ...(spotifyId ? { spotifyId } : {}) });
      }
      if (!matched.length) throw new EmptySourceError();

      if (!existing) {
        const made = await insertPlaylist(
          tx,
          input.title,
          matched.map((m) => m.c),
          { sourceId: input.sourceId, coverUrl: input.coverUrl, fromSpotifyIds: matched.map((m) => m.spotifyId) },
          inserted,
        );
        first = made.first;
        return { playlistId: made.row.id, created: true, added: made.added, removed: 0, name: made.row.title };
      }

      const entries = await entriesIn(tx, existing.id);
      const rows = await rowsOf(tx, entries.map((e) => e.trackId));
      const byKey = new Map<string, Candidate>();
      for (const m of matched) if (!byKey.has(m.trackKey)) byKey.set(m.trackKey, m.c);
      const holderOf = (key: string): number | undefined => {
        const c = byKey.get(key);
        if (!c) return undefined;
        const same = c.rowId != null ? entries.find((e) => e.trackId === c.rowId) : undefined;
        if (same) return same.entryId;
        return entries.find((e) => {
          const row = rows.get(e.trackId);
          return row != null && sameSong(c.song, songOfRow(row));
        })?.entryId;
      };
      const plan = reconcile({
        existing: entries,
        sourceIds: input.listedSpotifyIds ?? null,
        complete: input.complete === true,
        matched,
        holderOf,
      });

      const store = tx.objectStore('playlist_entry');
      const gone = new Set(plan.remove);
      for (const entryId of plan.remove) await store.delete(entryId);
      const kept: PlaylistEntry[] = [];
      const retag = new Map(plan.retag.map((r) => [r.entryId, r.fromSpotifyId] as const));
      for (const e of entries) {
        if (gone.has(e.entryId)) continue;
        const tag = retag.get(e.entryId);
        if (tag) {
          const next = { ...e, fromSpotifyId: tag };
          await store.put(next);
          kept.push(next);
        } else {
          kept.push(e);
        }
      }
      if (gone.size) await renumber(tx, kept);
      let position = gone.size ? kept.length : entries.length ? entries[entries.length - 1]!.position + 1 : 0;
      for (const m of plan.add) {
        const trackId = await rowIdOf(tx, m.c, inserted);
        await store.add({ playlistId: existing.id, trackId, position: position++, ...(m.spotifyId ? { fromSpotifyId: m.spotifyId } : {}) });
      }

      const cover = storableUrl(input.coverUrl) ?? existing.coverUrl;
      const coverChanged = cover !== existing.coverUrl;
      if (coverChanged) art = await dropOwnedArt(tx, existing.coverUrl, existing.id);
      if (plan.add.length || gone.size || plan.retag.length || coverChanged) await touch(tx, existing.id, { coverUrl: cover });
      return { playlistId: existing.id, created: false, added: plan.add.length, removed: gone.size, name: existing.title };
    }),
  );
  notifyWrite(inserted, 'playlist', 'playlist_entry', ...(art ? (['local_art'] as const) : []));
  if (first) void requestPersistence();
  return result;
}

// ---- Reads -------------------------------------------------------------------------------------------------

/** The device copy of a cloud page, if one was saved (the oldest, should two exist). */
export async function bySource(sourceId: string): Promise<PlaylistRow | undefined> {
  const all = await (await openAudenDb()).getAll('playlist');
  return all.filter((p) => p.sourceId === sourceId).sort((a, b) => a.createdAt - b.createdAt)[0];
}

export async function getPlaylist(id: string): Promise<PlaylistRow | undefined> {
  return (await openAudenDb()).get('playlist', id);
}

/** Every playlist, pinned first then newest first (Android's order), with its size and first thumbnails. */
export async function listPlaylists(): Promise<PlaylistSummary[]> {
  return read(async (tx) => {
    const playlists = await tx.objectStore('playlist').getAll();
    const out: PlaylistSummary[] = [];
    for (const playlist of playlists) {
      const entries = await entriesIn(tx, playlist.id);
      const thumbnails: string[] = [];
      for (const e of entries.slice(0, 40)) {
        const thumb = (await tx.objectStore('track').get(e.trackId))?.thumbnailUrl;
        if (thumb && !thumbnails.includes(thumb)) thumbnails.push(thumb);
        if (thumbnails.length === 4) break;
      }
      out.push({ playlist, count: entries.length, thumbnails });
    }
    return out.sort((a, b) => Number(b.playlist.pinned) - Number(a.playlist.pinned) || b.playlist.createdAt - a.playlist.createdAt);
  });
}

/** The playlist's entries in order, each with its track row. */
export async function entriesOf(playlistId: string): Promise<Array<{ entry: PlaylistEntry; row: TrackRow | null }>> {
  return read(async (tx) => {
    const entries = await entriesIn(tx, playlistId);
    const rows = await rowsOf(tx, entries.map((e) => e.trackId));
    return entries.map((entry) => ({ entry, row: rows.get(entry.trackId) ?? null }));
  });
}

/**
 * A row as the player's Track. A row with a YouTube id is a YouTube song (a linked file still plays from disk when
 * it can, and it stays shareable in a party); a file with no YouTube id is a local track, exactly as the library
 * shows it (trackOfRow). A row with neither cannot play: null.
 */
export function trackFromRow(row: TrackRow, art: ReadonlyMap<string, string>): Track | null {
  if (row.id == null) return null;
  if (row.videoId && !isSyntheticId(row.videoId)) {
    const key = artKeyFromUrl(row.thumbnailUrl);
    const names = splitArtists(row.artist);
    return {
      videoId: row.videoId,
      title: row.title,
      artists: (names.length ? names : [row.artist]).filter(Boolean).map((name, i) => ({ name, id: i === 0 ? row.artistId : null })),
      album: row.album ? { name: row.album, id: row.albumId } : null,
      durationSec: row.durationMs ? row.durationMs / 1000 : parseDurationText(row.durationText),
      explicit: false,
      thumbnail: key ? (art.get(key) ?? null) : row.thumbnailUrl,
      index: null,
      plays: null,
    };
  }
  if (row.localUri) return trackOfRow(row, art);
  return null;
}

const NO_ART: ReadonlyMap<string, string> = new Map();

/** The playable songs of a playlist, in order. */
export async function playlistTracks(id: string, art: ReadonlyMap<string, string> = NO_ART): Promise<Track[]> {
  const out: Track[] = [];
  for (const { row } of await entriesOf(id)) {
    const track = row ? trackFromRow(row, art) : null;
    if (track) out.push(track);
  }
  return out;
}

/** Ids of the playlists that already hold the song (same row, or sameSong). Empty for what a playlist can't hold. */
export async function membership(track: Track): Promise<Set<string>> {
  return read(async (tx) => {
    const out = new Set<string>();
    const c = await tryCandidate(tx, track);
    if (!c) return out;
    const entries = (await tx.objectStore('playlist_entry').getAll()) as PlaylistEntry[];
    const rows = await rowsOf(tx, entries.map((e) => e.trackId));
    for (const e of entries) {
      if (out.has(e.playlistId)) continue;
      const row = rows.get(e.trackId);
      if (e.trackId === c.rowId || (row && sameSong(c.song, songOfRow(row)))) out.add(e.playlistId);
    }
    return out;
  });
}

// ---- Query hooks (kept fresh by startAudenQuerySync: entries also refetch when tracks change) -----------------

export function useLocalPlaylists() {
  return useQuery({ queryKey: audenKey('playlist', 'list'), queryFn: listPlaylists, staleTime: Infinity });
}

export function useLocalPlaylist(id: string | undefined) {
  return useQuery({
    queryKey: audenKey('playlist', 'one', id),
    queryFn: async () => (id ? ((await getPlaylist(id)) ?? null) : null),
    enabled: !!id,
    staleTime: Infinity,
  });
}

export function usePlaylistEntries(id: string | undefined): { items: PlaylistItem[]; loading: boolean; error: unknown } {
  const art = useLocalArt();
  const query = useQuery({
    queryKey: audenKey('playlist_entry', 'of', id),
    queryFn: () => (id ? entriesOf(id) : Promise.resolve([])),
    enabled: !!id,
    staleTime: Infinity,
  });
  const data = query.data;
  const items = useMemo(
    () => (data ?? []).map<PlaylistItem>(({ entry, row }) => ({ entryId: entry.entryId, entry, row, track: row ? trackFromRow(row, art) : null })),
    [data, art],
  );
  return { items, loading: query.isPending && !!id, error: query.error };
}

export function useMembership(track: Track | null | undefined) {
  return useQuery({
    queryKey: audenKey('playlist_entry', 'membership', track?.videoId ?? null),
    queryFn: () => (track ? membership(track) : Promise.resolve(new Set<string>())),
    enabled: !!track,
    staleTime: Infinity,
  });
}
