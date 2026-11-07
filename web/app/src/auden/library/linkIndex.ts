import { create } from 'zustand';
import type { Track } from '@/data/music/types';
import { localRowIdOf, sourceOf } from '@/playback/source';
import { parseLocalUri } from '../db/libraryDao';
import type { TrackRow } from '../db/schema';
import { type IdentitySong, normalizeKey, parseDurationText, sameSong } from './identity';
import { isUsable } from './roots';

/**
 * Which indexed files go with which songs, in memory. `hasLink` runs on the hot path of every YouTube play
 * once the library is installed, so it answers from maps: a negative is two lookups and a cache hit.
 * Links are by YouTube id (identification wrote it on the row) and by metadata (Android's SongIdentity).
 */

export interface LinkEntry {
  rowId: number;
  rootId: string;
  relPath: string;
  row: TrackRow;
  song: IdentitySong;
}

let byRowId = new Map<number, LinkEntry>();
let byVideoId = new Map<string, LinkEntry[]>();
let byTitle = new Map<string, LinkEntry[]>();
let cache = new Map<string, LinkEntry[]>();

const songOfRow = (row: TrackRow): IdentitySong => ({
  videoId: row.videoId,
  localUri: row.localUri,
  title: row.title,
  artist: row.artist,
  durationSec: row.durationMs ? row.durationMs / 1000 : parseDurationText(row.durationText),
});

const songOfTrack = (track: Track): IdentitySong => ({
  videoId: track.videoId,
  title: track.title,
  artist: track.artists.map((a) => a.name).join(', '),
  durationSec: track.durationSec,
});

function push<K>(map: Map<K, LinkEntry[]>, key: K, entry: LinkEntry): void {
  const list = map.get(key);
  if (list) list.push(entry);
  else map.set(key, [entry]);
}

/** Rebuilds from the rows (every row with a file). */
export function rebuildLinkIndex(rows: readonly TrackRow[]): void {
  const rowMap = new Map<number, LinkEntry>();
  const idMap = new Map<string, LinkEntry[]>();
  const titleMap = new Map<string, LinkEntry[]>();
  for (const row of rows) {
    if (!row.localUri || row.id == null) continue;
    const parsed = parseLocalUri(row.localUri);
    if (!parsed) continue;
    const entry: LinkEntry = { rowId: row.id, rootId: parsed.rootId, relPath: parsed.relPath, row, song: songOfRow(row) };
    rowMap.set(row.id, entry);
    if (row.videoId) push(idMap, row.videoId, entry);
    const key = normalizeKey(row.title);
    if (key) push(titleMap, key, entry);
  }
  byRowId = rowMap;
  byVideoId = idMap;
  byTitle = titleMap;
  cache = new Map();
  useLinkVersion.setState((s) => ({ v: s.v + 1 }));
}

/** Bumps whenever the index is rebuilt, so rows showing the "on this device" mark re-render. */
export const useLinkVersion = create<{ v: number }>(() => ({ v: 0 }));

export function clearLinkCache(): void {
  cache = new Map();
  useLinkVersion.setState((s) => ({ v: s.v + 1 }));
}

export function entryByRowId(id: number): LinkEntry | undefined {
  return byRowId.get(id);
}

/** Every file that goes with [track], readable or not. */
export function linksOf(track: Track): LinkEntry[] {
  if (sourceOf(track) === 'local') {
    const id = Number(localRowIdOf(track.videoId));
    const entry = Number.isFinite(id) ? byRowId.get(id) : undefined;
    return entry ? [entry] : [];
  }
  if (sourceOf(track) !== 'youtube') return [];
  const hit = cache.get(track.videoId);
  if (hit) return hit;
  const found = new Map<number, LinkEntry>();
  for (const entry of byVideoId.get(track.videoId) ?? []) found.set(entry.rowId, entry);
  const bucket = byTitle.get(normalizeKey(track.title));
  if (bucket) {
    const song = songOfTrack(track);
    for (const entry of bucket) if (!found.has(entry.rowId) && sameSong(song, entry.song)) found.set(entry.rowId, entry);
  }
  const list = [...found.values()];
  cache.set(track.videoId, list);
  return list;
}

/** A readable file goes with [track] right now (its folder is connected). Synchronous. */
export function hasLink(track: Track): boolean {
  const links = linksOf(track);
  return links.length > 0 && links.some((l) => isUsable(l.rootId));
}

/** A file goes with [track] whether or not its folder is connected: the "on this device" mark. */
export function isOnDevice(track: Track): boolean {
  return linksOf(track).length > 0;
}
