import type { Track } from '@/data/music/types';
import { openAudenDb, type TrackRow } from '../db/schema';
import { parseDurationText } from '../library/identity';
import { AudenJson, M3u, parsePlaylist, Paths, PlaylistFormat, sanitizeVideoId, Xspf, type PlaylistEntry } from './files';
import { PlaylistMatcher, type MatchableTrack } from './matcher';
import { createPlaylist, entriesOf, getPlaylist, listPlaylists } from './store';

/** Playlist files from other players are untrusted and can be huge: nothing past this is read. */
export const MAX_IMPORT_BYTES = 8 * 1024 * 1024;

export interface ExportedFile {
  fileName: string;
  mime: string;
  text: string;
}

export type ExportFormat = keyof typeof PlaylistFormat;

const entryOfRow = (row: TrackRow): PlaylistEntry => ({
  // Relative to the folder the song was indexed from: the same path the matcher finds again on import.
  path: row.localUri ? row.path : null,
  title: row.title,
  artist: row.artist === 'Unknown Artist' ? null : row.artist,
  album: row.album,
  durationSec: row.durationMs ? row.durationMs / 1000 : parseDurationText(row.durationText),
  videoId: sanitizeVideoId(row.videoId),
});

/** The playlist as a file. M3U8 only has room for songs that have a file; XSPF and Auden JSON keep the rest. */
export async function exportPlaylist(playlistId: string, format: ExportFormat): Promise<ExportedFile | null> {
  const playlist = await getPlaylist(playlistId);
  if (!playlist) return null;
  const entries = (await entriesOf(playlistId)).flatMap((i) => (i.row ? [entryOfRow(i.row)] : []));
  const spec = PlaylistFormat[format];
  const text = format === 'M3U8' ? M3u.write(playlist.title, entries, null) : format === 'XSPF' ? Xspf.write(playlist.title, entries, null) : AudenJson.write(playlist.title, entries);
  return { fileName: `${Paths.safeFileName(playlist.title)}.${spec.extension}`, mime: spec.mime, text };
}

export interface ImportSummary {
  playlistId: string;
  name: string;
  total: number;
  local: number;
  online: number;
  /** Lines nothing could be matched to: reported, not stored. */
  unresolved: Array<{ title: string; artist: string }>;
}

export type OnlineSearch = (entry: PlaylistEntry) => Promise<Track | null>;

const matchable = (row: TrackRow): MatchableTrack => ({
  id: row.id!,
  videoId: row.videoId,
  path: row.path,
  title: row.title,
  artist: row.artist,
  durationSec: row.durationMs ? row.durationMs / 1000 : parseDurationText(row.durationText),
});

/** A song the file names only by video id (Auden JSON from another device): playable by YouTube without any lookup. */
function trackOfEntry(entry: PlaylistEntry, videoId: string): Track {
  return {
    videoId,
    title: entry.title?.trim() || videoId,
    artists: entry.artist ? [{ name: entry.artist, id: null }] : [],
    album: entry.album ? { name: entry.album, id: null } : null,
    durationSec: entry.durationSec ?? null,
    explicit: false,
    thumbnail: null,
    index: null,
    plays: null,
  };
}

const labelOf = (e: PlaylistEntry) => ({
  title: e.title?.trim() || (e.path ? Paths.fileName(e.path).replace(/\.[^.]+$/, '') : '') || 'Unknown',
  artist: e.artist?.trim() ?? '',
});

/**
 * Reads a playlist file into a new playlist. Each line resolves to a library file (path, file name, tags), then
 * to a YouTube video by its id, then, when [search] is given, to a YouTube Music search result. Lines that
 * resolve to nothing are listed in the summary. Throws with a plain message when the file can't be used.
 */
export const MAX_ONLINE_SEARCHES = 500;
const SEARCH_PARALLELISM = 4;

export interface ImportOptions {
  search?: OnlineSearch;
  /** Past this many online searches the rest are reported as not found (a file can have 10,000 lines). */
  maxSearches?: number;
  onProgress?: (done: number, total: number) => void;
  signal?: AbortSignal;
}

export async function importPlaylistFile(file: File, opts: ImportOptions = {}): Promise<ImportSummary> {
  if (file.size > MAX_IMPORT_BYTES) throw new Error('That playlist file is too big (over 8 MB).');
  const parsed = parsePlaylist(file.name, await file.text(), null);
  if (!parsed) throw new Error('That file isn’t a playlist Auden can read.');
  if (!parsed.entries.length) throw new Error('That playlist is empty.');

  const db = await openAudenDb();
  const matcher = new PlaylistMatcher((await db.getAll('track')).filter((r) => r.id != null && (r.localUri || r.videoId)).map(matchable));
  // Pass 1, no network: library, then video id. Pass 2: online search for what is left, a few at a time, capped.
  const slots: Array<{ pick: Track | number | null; entry: PlaylistEntry; from: 'local' | 'online' | null }> = parsed.entries.map((entry) => {
    const hit = matcher.match(entry);
    if (hit) return { pick: hit.id, entry, from: 'local' };
    const videoId = sanitizeVideoId(entry.videoId);
    return videoId ? { pick: trackOfEntry(entry, videoId), entry, from: 'local' } : { pick: null, entry, from: null };
  });
  const open = slots.filter((s) => s.pick === null);
  const budget = opts.search ? Math.min(open.length, opts.maxSearches ?? MAX_ONLINE_SEARCHES) : 0;
  let done = 0;
  const total = parsed.entries.length;
  opts.onProgress?.(0, budget || total);
  let next = 0;
  await Promise.all(
    Array.from({ length: Math.min(SEARCH_PARALLELISM, budget) }, async () => {
      while (next < budget && !opts.signal?.aborted) {
        const slot = open[next++]!;
        const found = await opts.search!(slot.entry).catch(() => null);
        if (found) Object.assign(slot, { pick: found, from: 'online' });
        opts.onProgress?.(++done, budget);
      }
    }),
  );
  if (opts.signal?.aborted) throw new Error('Import cancelled.');
  const picks: Array<Track | number> = [];
  const unresolved: ImportSummary['unresolved'] = [];
  let local = 0;
  let online = 0;
  for (const slot of slots) {
    if (slot.pick === null) unresolved.push(labelOf(slot.entry));
    else {
      picks.push(slot.pick);
      if (slot.from === 'online') online++;
      else local++;
    }
  }
  if (!picks.length) throw new Error('None of the songs in that playlist could be found.');

  const base = parsed.name?.trim() || file.name.replace(/\.(auden\.json|m3u8?|xspf|json)$/i, '') || 'Imported Playlist';
  const taken = new Set((await listPlaylists()).map((p) => p.playlist.title));
  const name = uniqueTitle(base, taken);
  const created = await createPlaylist(name, picks);
  return { playlistId: created.id, name, total: parsed.entries.length, local, online, unresolved };
}

/** "Mix", then "Mix (2)", "Mix (3)"… (Android's uniqueName rule, for titles). */
export function uniqueTitle(base: string, taken: ReadonlySet<string>): string {
  if (!taken.has(base)) return base;
  for (let n = 2; ; n++) if (!taken.has(`${base} (${n})`)) return `${base} (${n})`;
}

/** A summary line for the banner. */
export function describeImport(s: ImportSummary): string {
  const found = s.local + s.online;
  const miss = s.unresolved.length;
  return miss ? `“${s.name}”: ${found} of ${s.total} songs found, ${miss} not found.` : `“${s.name}”: ${found} songs imported.`;
}
