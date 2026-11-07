import { openAudenDb, type DayStats, type NameEntry, type StoredBucket, type TrackEntry } from '../db/schema';
import { APP_TAG, SCHEMA_VERSION, suggestedBackupName, type BackupEntry, type BackupFile, type BackupTrack, type LibraryData } from './format';
import { readHeldSettings, type HeldSettings } from './passthrough';

/**
 * Writes the library, the lyrics, the Spotify matches and the listening as the file the PHONE reads. What
 * the phone would choke on or destroy is mapped before it goes: every Long/Int is a whole number (kotlinx
 * refuses 5000.3 for a Long, and one such value rejects the whole file), nothing non-nullable is null,
 * ids are real, positions are dense, and no entry points at a song or playlist that is not in the file
 * (Room enforces foreign keys inside its restore). Files that exist only in a browser folder cannot travel:
 * the phone would delete one with no video id at its next index, so those are left out and counted.
 */

export interface ExportReport {
  songs: number;
  playlists: number;
  entries: number;
  lyrics: number;
  matches: number;
  months: number;
  /** Songs that are only a file in a browser folder: not exported, and dropped from the playlists that held them. */
  webFilesLeftOut: number;
  /** Playlist entries not exported (their song was left out, or was missing). */
  entriesLeftOut: number;
  /** The phone's settings the file carries back unchanged, or null: restoring a file without them resets the phone's. */
  phoneSettings: { capturedAt: string; versionName: string } | null;
}

export interface Snapshot {
  tracks: (BackupTrack | (Omit<BackupTrack, 'id'> & { id?: number }))[];
  playlists: LibraryData['playlists'];
  entries: (BackupEntry | (Omit<BackupEntry, 'entryId'> & { entryId?: number }))[];
  lyricsVariants: LibraryData['lyricsVariants'];
  lyricsPrefs: LibraryData['lyricsPrefs'];
  spotifyMatches: LibraryData['spotifyMatches'];
  listening: StoredBucket[];
}

const WEB_FILE = 'auden-file://';
const WEB_ART = 'auden-art://';
const LOCAL_PREFIX = 'local:playlist:';
const PHONE_PREFIX = 'sp_local_';

/** A whole number for a Kotlin Long/Int; null stays null, anything not finite is dropped. */
const int = (n: number | null | undefined): number | null => (typeof n === 'number' && Number.isFinite(n) ? Math.round(n) : null);
const int0 = (n: number | null | undefined): number => int(n) ?? 0;

/** The id the phone knows a playlist by: its own `sp_local_` form (its lookups strip `local:playlist:`, so the web's would miss). */
export const phonePlaylistId = (id: string): string => (id.startsWith(LOCAL_PREFIX) ? PHONE_PREFIX + id.slice(LOCAL_PREFIX.length) : id);

const days = (d: Record<string, DayStats> | null): Record<string, DayStats> | null =>
  d && Object.fromEntries(Object.entries(d).map(([k, v]) => [k, { ms: int0(v.ms), plays: int0(v.plays) }]));

const portableArt = (art: string | null | undefined): string | null => (art && !art.startsWith('blob:') && !art.startsWith(WEB_ART) ? art : null);

function name(e: NameEntry): NameEntry {
  // Kotlin refuses a null where a String is declared, whatever its default.
  return { ...e, name: e.name ?? '', art: portableArt(e.art), ms: int0(e.ms), plays: int0(e.plays), days: days(e.days) ?? {} };
}

function bucket(b: StoredBucket): StoredBucket {
  const tracks: TrackEntry[] = b.tracks
    // A file in a browser folder has no meaning on the phone, and its id would be a dangling `auden:local:` one.
    .filter((t) => !t.id.startsWith('auden:'))
    .map((t) => ({ ...t, title: t.title ?? '', artist: t.artist ?? '', art: portableArt(t.art), ms: int0(t.ms), plays: int0(t.plays), last: int0(t.last), days: days(t.days) ?? {} }));
  const hours = Array.from({ length: 24 }, (_, i) => int0(b.hours[i]));
  const dayTotals: Record<number, number> = {};
  for (const [k, v] of Object.entries(b.days)) dayTotals[Number(k)] = int0(v);
  return {
    version: b.version,
    month: b.month,
    tracks,
    artists: b.artists.map(name),
    albums: b.albums.map(name),
    hours,
    days: dayTotals,
    trackDays: days(b.trackDays),
    artistDays: days(b.artistDays),
    albumDays: days(b.albumDays),
  };
}

/** Builds the file from a snapshot of the stores. Pure: the same snapshot gives the same file. */
export function buildBackup(
  snapshot: Snapshot,
  held: HeldSettings | null,
  { now = new Date(), versionName = 'web' }: { now?: Date; versionName?: string } = {},
): { file: BackupFile; report: ExportReport } {
  // Songs: which travel, and under which fields.
  const tracks: BackupTrack[] = [];
  const kept = new Set<number>();
  let webFilesLeftOut = 0;
  const leftOutKeys = new Set<string>();
  for (const t of snapshot.tracks) {
    if (typeof t.id !== 'number') continue;
    const onWeb = !!t.localUri?.startsWith(WEB_FILE);
    if (onWeb && !t.videoId) {
      webFilesLeftOut++;
      leftOutKeys.add(t.localUri!);
      continue;
    }
    // A song the phone had a file for gets it back exactly; a web file with a video id travels as a plain stream
    // (the phone would detach it at its next index anyway).
    const phone = t.androidFile;
    const { androidFile: _stash, ...row } = t;
    void _stash;
    tracks.push({
      ...row,
      id: t.id,
      title: t.title ?? '',
      artist: t.artist ?? '',
      localUri: phone ? phone.localUri : onWeb ? null : t.localUri,
      path: phone ? phone.path : onWeb ? null : t.path,
      folder: phone ? phone.folder : onWeb ? null : t.folder,
      sizeBytes: int(phone ? phone.sizeBytes : onWeb ? null : t.sizeBytes),
      modifiedAt: int(phone ? phone.modifiedAt : onWeb ? null : t.modifiedAt),
      durationMs: int(t.durationMs),
      trackNumber: int(t.trackNumber),
      discNumber: int(t.discNumber),
      year: int(t.year),
      isVideo: t.isVideo === true,
      origin: t.origin || 'YTM',
      addedAt: int0(t.addedAt),
      identifiedAt: int(t.identifiedAt),
    });
    kept.add(t.id);
  }

  // Playlists, under the phone's ids; their covers that live only in this browser are not carried.
  const idMap = new Map<string, string>();
  const playlists = snapshot.playlists.map((p) => {
    const id = phonePlaylistId(p.id);
    idMap.set(p.id, id);
    return {
      ...p,
      id,
      createdAt: int0(p.createdAt),
      updatedAt: int0(p.updatedAt),
      pinned: p.pinned === true,
      coverUrl: p.coverUrl?.startsWith(WEB_ART) ? null : p.coverUrl,
    };
  });

  // Entries: only those whose song and playlist are in the file, positions dense from 0 in their old order.
  const byPlaylist = new Map<string, typeof snapshot.entries>();
  let entriesLeftOut = 0;
  for (const e of snapshot.entries) {
    const playlistId = idMap.get(e.playlistId);
    if (!playlistId || !kept.has(e.trackId)) {
      entriesLeftOut++;
      continue;
    }
    byPlaylist.set(playlistId, [...(byPlaylist.get(playlistId) ?? []), { ...e, playlistId }]);
  }
  const entries: BackupEntry[] = [];
  let nextEntryId = 1;
  for (const [, list] of [...byPlaylist].sort(([a], [b]) => (a < b ? -1 : 1))) {
    list
      .sort((a, b) => a.position - b.position || (a.entryId ?? 0) - (b.entryId ?? 0))
      .forEach((e, position) => entries.push({ ...e, entryId: nextEntryId++, position }));
  }

  // Lyrics: keys that are files in a browser folder mean nothing to the phone; a choice must name a variant that is there.
  const lyricsVariants = snapshot.lyricsVariants.filter((v) => !v.songKey.startsWith(WEB_FILE)).map((v) => ({ ...v, fetchedAt: int0(v.fetchedAt) }));
  const have = new Set(lyricsVariants.map((v) => `${v.songKey}\u0000${v.provider}`));
  const lyricsPrefs = snapshot.lyricsPrefs
    .filter((p) => !p.songKey.startsWith(WEB_FILE))
    .map((p) => ({
      songKey: p.songKey,
      // The phone only makes a download active when nothing is chosen: a choice with nothing behind it would stop that.
      activeProvider: p.activeProvider && have.has(`${p.songKey}\u0000${p.activeProvider}`) ? p.activeProvider : null,
      offsetMs: int(p.offsetMs),
    }))
    .filter((p) => p.activeProvider !== null || p.offsetMs !== null);

  const library: LibraryData = {
    tracks,
    playlists,
    entries,
    lyricsVariants,
    lyricsPrefs,
    spotifyMatches: snapshot.spotifyMatches.map((m) => ({ ...m, matchedAt: int0(m.matchedAt) })),
  };
  const listening = [...snapshot.listening].sort((a, b) => (a.month < b.month ? -1 : 1)).map(bucket);

  const file: BackupFile = {
    app: APP_TAG,
    version: SCHEMA_VERSION,
    versionName,
    exportedAt: now.toISOString(),
    settings: held?.settings ?? {},
    listening,
    library,
    origin: 'web',
    ...(held && { settingsCapturedAt: held.capturedAt }),
  };
  return {
    file,
    report: {
      songs: tracks.length,
      playlists: playlists.length,
      entries: entries.length,
      lyrics: lyricsVariants.length,
      matches: library.spotifyMatches.length,
      months: listening.length,
      webFilesLeftOut,
      entriesLeftOut,
      phoneSettings: held ? { capturedAt: held.capturedAt, versionName: held.versionName } : null,
    },
  };
}

/** Reads every store at one instant. */
export async function readSnapshot(): Promise<Snapshot> {
  const db = await openAudenDb();
  const tx = db.transaction(['track', 'playlist', 'playlist_entry', 'lyrics_variant', 'song_lyrics_pref', 'spotify_match', 'listening']);
  const [tracks, playlists, entries, lyricsVariants, lyricsPrefs, spotifyMatches, listening] = await Promise.all([
    tx.objectStore('track').getAll(),
    tx.objectStore('playlist').getAll(),
    tx.objectStore('playlist_entry').getAll(),
    tx.objectStore('lyrics_variant').getAll(),
    tx.objectStore('song_lyrics_pref').getAll(),
    tx.objectStore('spotify_match').getAll(),
    tx.objectStore('listening').getAll(),
  ]);
  await tx.done;
  return { tracks, playlists, entries, lyricsVariants, lyricsPrefs, spotifyMatches, listening };
}

/**
 * What would make the phone refuse or mangle the file, found before it is written: Room's restore enforces the
 * foreign keys and a unique file uri (a duplicate would silently replace the earlier row and strand its
 * entries). Empty when the file is sound.
 */
export function selfCheck(file: BackupFile): string[] {
  const problems: string[] = [];
  const lib = file.library;
  if (!lib) return problems;
  const songs = new Set(lib.tracks.map((t) => t.id));
  const lists = new Set(lib.playlists.map((p) => p.id));
  if (songs.size !== lib.tracks.length) problems.push('two songs share an id');
  if (lists.size !== lib.playlists.length) problems.push('two playlists share an id');
  const uris = lib.tracks.map((t) => t.localUri).filter((u): u is string => !!u);
  if (new Set(uris).size !== uris.length) problems.push('two songs name the same file');
  if (lib.entries.some((e) => !songs.has(e.trackId) || !lists.has(e.playlistId))) problems.push('a playlist entry points at nothing');
  if (lib.tracks.some((t) => !Number.isSafeInteger(t.id) || t.id < 1)) problems.push('a song has no usable id');
  return problems;
}

/** The backup as text, its suggested name and what it holds and leaves out. */
export async function exportBackup(versionName = 'web'): Promise<{ text: string; name: string; report: ExportReport }> {
  const { file, report } = buildBackup(await readSnapshot(), readHeldSettings(), { versionName });
  const problems = selfCheck(file);
  // Better no file than one the phone would refuse, or one that makes it lose a song's place in a playlist.
  if (problems.length) throw new Error(`The backup was not written: ${problems.join('; ')}.`);
  // Android pretty-prints; so does this, so the two read alike in a diff.
  return { text: JSON.stringify(file, null, 2), name: suggestedBackupName(), report };
}
