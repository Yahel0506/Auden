import { splitArtists } from '../artistSplit';
import { normalizeKey } from '../identity';

/** The incremental part of indexing, free of I/O so it can be tested: what to parse, and what is gone. */

export const AUDIO_EXTENSIONS = ['mp3', 'm4a', 'aac', 'flac', 'ogg', 'oga', 'opus', 'wav', 'weba', 'wma', 'aif', 'aiff', 'alac', 'mka'];

export function isAudioName(name: string): boolean {
  const dot = name.lastIndexOf('.');
  return dot > 0 && AUDIO_EXTENSIONS.includes(name.slice(dot + 1).toLowerCase());
}

export interface KnownFile {
  size: number;
  modifiedAt: number;
}

/** Parse a file when it is new, or its size or modified time moved (Android: modifiedAt or sizeBytes differ). */
export function needsParse(known: KnownFile | undefined, size: number, modifiedAt: number): boolean {
  return !known || known.size !== size || known.modifiedAt !== modifiedAt;
}

/**
 * Which known paths are gone. Only a *complete* walk may say so: a folder that threw mid-walk (renamed,
 * a removable drive, permission lost) looks exactly like a folder with no files in it, and deleting the
 * library on that evidence would be the worst possible failure. An empty walk of a root that had files
 * is treated the same way (Android: "scan returned no files").
 */
export function planGone(knownPaths: Iterable<string>, seen: Iterable<string>, complete: boolean): string[] {
  if (!complete) return [];
  const present = new Set(seen);
  const known = [...knownPaths];
  if (present.size === 0 && known.length > 0) return [];
  return known.filter((path) => !present.has(path));
}

/** `Music/Rock/a.mp3` for a File picked with webkitdirectory, relative to the picked folder (drops its own name). */
export function relPathOf(webkitRelativePath: string): { root: string; relPath: string } | null {
  const parts = webkitRelativePath.split('/').filter(Boolean);
  if (parts.length < 2) return null;
  return { root: parts[0]!, relPath: parts.slice(1).join('/') };
}

/** `a/b/c.mp3` → `a/b`; a file at the root has an empty folder. */
export function folderOf(relPath: string): string {
  const slash = relPath.lastIndexOf('/');
  return slash < 0 ? '' : relPath.slice(0, slash);
}

/** The cover shared by an album (or, with no album tag, by a folder). Same key in the Worker and on the main thread. */
export function artKeyOf(tags: { album: string | null; albumArtist: string | null; artist: string | null } | null, relPath: string): string {
  if (tags?.album) return `album:${normalizeKey(tags.albumArtist || splitArtists(tags.artist || '')[0] || '')}|${normalizeKey(tags.album)}`;
  return `folder:${folderOf(relPath)}`;
}


/** Rows keep a cover as `auden-art://<key>` (Android keeps a URL there); the UI turns it into a blob: URL (library/art.ts). */
export const ART_SCHEME = 'auden-art://';
export const artUrlOf = (key: string): string => ART_SCHEME + encodeURIComponent(key);
export function artKeyFromUrl(url: string | null | undefined): string | null {
  if (!url || !url.startsWith(ART_SCHEME)) return null;
  try {
    return decodeURIComponent(url.slice(ART_SCHEME.length));
  } catch {
    return null;
  }
}

/**
 * Whether a walk of a root that has no folder handle (Firefox: a picked folder) is plausibly the same folder
 * the index came from. Without a handle only the folder's name ties a pick to a root, and another folder
 * called "Music" would otherwise read as a library whose every song vanished. No shared path at all, or fewer
 * than half of the known ones, means: don't remove anything because of this pick.
 */
export function plausiblySameFolder(knownPaths: readonly string[], seen: Iterable<string>): boolean {
  if (knownPaths.length === 0) return true;
  const present = new Set(seen);
  const shared = knownPaths.filter((p) => present.has(p)).length;
  return shared > 0 && shared * 2 >= knownPaths.length;
}
