// Port of the pure grouping in com/music/bitchord/data/LocalGroups.kt (local tracks only):
// artistsOf, albumsOf, genresOf, foldersOf.

import { splitArtists } from './artistSplit';

export interface LocalSong {
  id: number;
  title: string;
  artist: string;
  album?: string | null;
  albumArtist?: string | null;
  genre?: string | null;
  /** Full directory path of the file. */
  folder?: string | null;
  trackNumber?: number | null;
  discNumber?: number | null;
  year?: number | null;
  durationMs?: number | null;
}

export interface LocalGroup<T extends LocalSong = LocalSong> {
  key: string;
  title: string;
  subtitle?: string;
  songs: T[];
}

const lower = (s: string) => s.toLowerCase();

function compareTitles(a: string, b: string): number {
  const la = lower(a);
  const lb = lower(b);
  return la < lb ? -1 : la > lb ? 1 : 0;
}

/** Plain UTF-16 order, as Kotlin's String.compareTo. */
function compareStrings(a = '', b = ''): number {
  return a < b ? -1 : a > b ? 1 : 0;
}

/** Title order, case-insensitive (the Kotlin `sortedByTitle`). */
function byTitle<T extends LocalSong>(songs: T[]): T[] {
  return [...songs].sort((a, b) => compareTitles(a.title, b.title));
}

/** Null/absent sorts after present values. */
function compareNullableNumber(a: number | null | undefined, b: number | null | undefined): number {
  const av = a ?? Number.POSITIVE_INFINITY;
  const bv = b ?? Number.POSITIVE_INFINITY;
  return av < bv ? -1 : av > bv ? 1 : 0;
}

/** Release order: disc, then track, then title. Missing values sort last. */
function inReleaseOrder<T extends LocalSong>(songs: T[]): T[] {
  return [...songs].sort(
    (a, b) =>
      compareNullableNumber(a.discNumber, b.discNumber) ||
      compareNullableNumber(a.trackNumber, b.trackNumber) ||
      compareTitles(a.title, b.title),
  );
}

/** Groups items by a string key, preserving first-seen key order and input order within each group. */
function groupBy<I, T>(items: I[], keyOf: (item: I) => string | null | undefined, valueOf: (item: I) => T): Map<string, T[]> {
  const out = new Map<string, T[]>();
  for (const item of items) {
    const key = keyOf(item);
    if (key == null) continue;
    const bucket = out.get(key);
    if (bucket) bucket.push(valueOf(item));
    else out.set(key, [valueOf(item)]);
  }
  return out;
}

/** The Artists tab: a collaboration is listed under every artist on it. Songs in title order. */
export function artistsOf<T extends LocalSong>(songs: T[]): LocalGroup<T>[] {
  const pairs: { name: string; song: T }[] = [];
  for (const song of songs) {
    const names = splitArtists(song.artist);
    for (const name of names.length > 0 ? names : [song.artist]) pairs.push({ name, song });
  }
  const groups = groupBy(pairs, (p) => p.name, (p) => p.song);
  return [...groups.entries()]
    .map(([name, group]) => ({ key: name, title: name, songs: byTitle(group) }))
    .sort((a, b) => compareTitles(a.title, b.title));
}

/**
 * The Albums tab: grouped by exact album name; blank or missing names are skipped.
 * Subtitle is the first song's artist (input order), as in Kotlin.
 * Songs are in release order (disc, track, title).
 */
export function albumsOf<T extends LocalSong>(songs: T[]): LocalGroup<T>[] {
  const groups = groupBy(
    songs,
    (s) => (s.album == null || s.album.trim() === '' ? null : s.album),
    (s) => s,
  );
  return [...groups.entries()]
    .map(([name, group]) => ({
      key: name,
      title: name,
      subtitle: group[0]?.artist ?? '',
      songs: inReleaseOrder(group),
    }))
    .sort((a, b) => compareTitles(a.title, b.title));
}

/** The Genres tab: exact genre tag; blank or missing genres are skipped. Songs in title order. */
export function genresOf<T extends LocalSong>(songs: T[]): LocalGroup<T>[] {
  const groups = groupBy(
    songs,
    (s) => (s.genre == null || s.genre.trim() === '' ? null : s.genre),
    (s) => s,
  );
  return [...groups.entries()]
    .map(([name, group]) => ({ key: name, title: name, songs: byTitle(group) }))
    .sort((a, b) => compareTitles(a.title, b.title));
}

/** Last path segment, as in Kotlin `directoryName`. */
export function directoryName(path: string): string {
  const trimmed = path.replace(/\/+$/, '');
  const i = trimmed.lastIndexOf('/');
  const last = i < 0 ? trimmed : trimmed.slice(i + 1);
  return last === '' ? path : last;
}

/** Kotlin `readablePath`: drops the volume prefix of the phone's storage, keeps a memory card's name. */
function readablePath(path: string): string {
  if (path.startsWith('/storage/emulated/0')) return path.slice('/storage/emulated/0'.length).replace(/^\/+/, '');
  if (path.startsWith('/storage/')) return path.slice('/storage/'.length);
  return path.replace(/^\/+/, '');
}

/** Parent path of a directory with the volume trimmed; '' when there is no parent. */
function parentOf(path: string): string {
  const i = path.lastIndexOf('/');
  return readablePath(i < 0 ? '' : path.slice(0, i));
}

/**
 * The Folders tab: grouped by the file's directory. Title is the last path segment,
 * subtitle the parent path. Blank or missing folders are skipped.
 * Songs in title order. Sorted by title, then subtitle.
 */
export function foldersOf<T extends LocalSong>(songs: T[]): LocalGroup<T>[] {
  const groups = groupBy(
    songs,
    (s) => (s.folder == null || s.folder.trim() === '' ? null : s.folder),
    (s) => s,
  );
  return [...groups.entries()]
    .map(([path, group]) => ({
      key: path,
      title: directoryName(path),
      subtitle: parentOf(path),
      songs: byTitle(group),
    }))
    .sort((a, b) => compareTitles(a.title, b.title) || compareStrings(a.subtitle, b.subtitle));
}
