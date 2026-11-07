import type {
  LyricsVariantRow,
  NameEntry,
  PlaylistEntryRow,
  PlaylistRow,
  SongLyricsPrefRow,
  SpotifyMatchRow,
  StoredBucket,
  TrackEntry,
  TrackRow,
} from '../db/schema';

/**
 * Auden's backup file, in the shape Android reads and writes (Backup.kt): one JSON object with the settings,
 * the listening and the library. This module is only the FORMAT: the types and a strict reader. The reader
 * refuses what the phone would refuse (a missing header, a newer version, a row of the wrong shape) and fills
 * in what Kotlin's defaults would, so nothing past it needs to guess.
 */

export const APP_TAG = 'auden';
/** What BitChord wrote in the same field; its files still import. */
export const LEGACY_APP_TAG = 'bitchord';
/** Bump when the shape stops being readable by an older build; a newer file is refused, not half-read. */
export const SCHEMA_VERSION = 2;
/** A backup is read whole into memory (about 5x its size once parsed): refuse past this, saying so. */
export const MAX_BACKUP_BYTES = 64 * 1024 * 1024;

export interface PrefValue {
  type: string;
  value?: string | null;
  values?: string[];
}

/** A song row with its id, as Room writes it. */
export type BackupTrack = TrackRow & { id: number };
export type BackupEntry = PlaylistEntryRow & { entryId: number };

export interface LibraryData {
  tracks: BackupTrack[];
  playlists: PlaylistRow[];
  entries: BackupEntry[];
  lyricsVariants: LyricsVariantRow[];
  lyricsPrefs: SongLyricsPrefRow[];
  spotifyMatches: SpotifyMatchRow[];
}

export interface BackupFile {
  app: string;
  version: number;
  versionName: string;
  exportedAt: string;
  /** The phone's preferences. The web never applies them: it keeps them to hand back unchanged. */
  settings: Record<string, PrefValue>;
  listening: StoredBucket[];
  /** Null in a BitChord file, or one from before the library existed: an import then leaves the library alone. */
  library: LibraryData | null;
  /** Written by the web, ignored by Android today: where the file came from and when the settings in it were captured. */
  origin?: 'web';
  settingsCapturedAt?: string;
}

export class BackupError extends Error {
  constructor(message: string) {
    super(message);
    this.name = 'BackupError';
  }
}

// ---- Reading ---------------------------------------------------------------------------------

type Obj = Record<string, unknown>;
const isObj = (v: unknown): v is Obj => !!v && typeof v === 'object' && !Array.isArray(v);

function fail(where: string, what: string): never {
  throw new BackupError(`That backup is damaged: ${where} ${what}.`);
}

const reqStr = (o: Obj, k: string, where: string): string => (typeof o[k] === 'string' ? (o[k] as string) : fail(where, `has no ${k}`));
const optStr = (o: Obj, k: string, where: string): string | null => {
  const v = o[k];
  if (v === undefined || v === null) return null;
  return typeof v === 'string' ? v : fail(where, `has a field "${k}" that is not text`);
};
const optNum = (o: Obj, k: string, where: string): number | null => {
  const v = o[k];
  if (v === undefined || v === null) return null;
  return typeof v === 'number' && Number.isFinite(v) ? v : fail(where, `has a field "${k}" that is not a number`);
};
const num = (o: Obj, k: string, where: string, fallback?: number): number => {
  const v = o[k];
  if (v === undefined && fallback !== undefined) return fallback;
  return typeof v === 'number' && Number.isFinite(v) ? v : fail(where, `has no ${k}`);
};
const bool = (o: Obj, k: string, where: string, fallback: boolean): boolean => {
  const v = o[k];
  if (v === undefined) return fallback;
  return typeof v === 'boolean' ? v : fail(where, `has a field "${k}" that is not true or false`);
};
/** A Room id: a whole number from 1 (0 means "assign one" to Room), and one a JS number can hold exactly. */
const rowId = (o: Obj, k: string, where: string): number => {
  const v = num(o, k, where);
  return Number.isSafeInteger(v) && v >= 1 ? v : fail(where, `has a field "${k}" that is not a usable id`);
};
const arr = (v: unknown, where: string): unknown[] => (v === undefined || v === null ? [] : Array.isArray(v) ? v : fail(where, 'is not a list'));
const row = (v: unknown, where: string): Obj => (isObj(v) ? v : fail(where, 'has an entry that is not an object'));

function readTrack(v: unknown, now: number): BackupTrack {
  const o = row(v, 'the songs');
  const w = 'a song';
  return {
    id: rowId(o, 'id', w),
    videoId: optStr(o, 'videoId', w),
    spotifyId: optStr(o, 'spotifyId', w),
    isrc: optStr(o, 'isrc', w),
    localUri: optStr(o, 'localUri', w),
    path: optStr(o, 'path', w),
    title: reqStr(o, 'title', w),
    artist: reqStr(o, 'artist', w),
    artistId: optStr(o, 'artistId', w),
    album: optStr(o, 'album', w),
    albumId: optStr(o, 'albumId', w),
    thumbnailUrl: optStr(o, 'thumbnailUrl', w),
    durationMs: optNum(o, 'durationMs', w),
    durationText: optStr(o, 'durationText', w),
    trackNumber: optNum(o, 'trackNumber', w),
    discNumber: optNum(o, 'discNumber', w),
    albumArtist: optStr(o, 'albumArtist', w),
    genre: optStr(o, 'genre', w),
    year: optNum(o, 'year', w),
    folder: optStr(o, 'folder', w),
    sizeBytes: optNum(o, 'sizeBytes', w),
    modifiedAt: optNum(o, 'modifiedAt', w),
    isVideo: bool(o, 'isVideo', w, false),
    origin: o.origin === undefined ? 'YTM' : reqStr(o, 'origin', w),
    addedAt: num(o, 'addedAt', w, now),
    identStatus: optStr(o, 'identStatus', w),
    identSource: optStr(o, 'identSource', w),
    suggestedVideoId: optStr(o, 'suggestedVideoId', w),
    suggestedTitle: optStr(o, 'suggestedTitle', w),
    suggestedArtist: optStr(o, 'suggestedArtist', w),
    identifiedAt: optNum(o, 'identifiedAt', w),
  };
}

function readPlaylist(v: unknown, now: number): PlaylistRow {
  const o = row(v, 'the playlists');
  const w = 'a playlist';
  const createdAt = num(o, 'createdAt', w, now);
  return {
    id: reqStr(o, 'id', w),
    title: reqStr(o, 'title', w),
    createdAt,
    updatedAt: num(o, 'updatedAt', w, createdAt),
    pinned: bool(o, 'pinned', w, false),
    sourceId: optStr(o, 'sourceId', w),
    coverUrl: optStr(o, 'coverUrl', w),
  };
}

function readEntry(v: unknown): BackupEntry {
  const o = row(v, 'the playlist entries');
  const w = 'a playlist entry';
  const fromSpotifyId = optStr(o, 'fromSpotifyId', w);
  return {
    entryId: rowId(o, 'entryId', w),
    playlistId: reqStr(o, 'playlistId', w),
    trackId: rowId(o, 'trackId', w),
    position: num(o, 'position', w),
    ...(fromSpotifyId && { fromSpotifyId }),
  };
}

function readVariant(v: unknown, now: number): LyricsVariantRow {
  const o = row(v, 'the saved lyrics');
  const w = 'a saved lyric';
  return {
    songKey: reqStr(o, 'songKey', w),
    provider: reqStr(o, 'provider', w),
    format: reqStr(o, 'format', w),
    content: reqStr(o, 'content', w),
    fetchedAt: num(o, 'fetchedAt', w, now),
  };
}

function readPref(v: unknown): SongLyricsPrefRow {
  const o = row(v, 'the lyrics choices');
  const w = 'a lyrics choice';
  return { songKey: reqStr(o, 'songKey', w), activeProvider: optStr(o, 'activeProvider', w), offsetMs: optNum(o, 'offsetMs', w) };
}

function readMatch(v: unknown, now: number): SpotifyMatchRow {
  const o = row(v, 'the Spotify matches');
  const w = 'a Spotify match';
  return { spotifyId: reqStr(o, 'spotifyId', w), status: reqStr(o, 'status', w), songJson: optStr(o, 'songJson', w), matchedAt: num(o, 'matchedAt', w, now) };
}

const readDays = (v: unknown, where: string): Record<string, { ms: number; plays: number }> => {
  const out: Record<string, { ms: number; plays: number }> = {};
  if (v === undefined || v === null) return out;
  if (!isObj(v)) fail(where, 'has days that are not an object');
  for (const [k, d] of Object.entries(v)) {
    const o = row(d, where);
    out[k] = { ms: num(o, 'ms', where, 0), plays: num(o, 'plays', where, 0) };
  }
  return out;
};

function readName(v: unknown, where: string): NameEntry {
  const o = row(v, where);
  return {
    name: o.name === undefined ? '' : reqStr(o, 'name', where),
    sub: optStr(o, 'sub', where),
    art: optStr(o, 'art', where),
    id: optStr(o, 'id', where),
    ms: num(o, 'ms', where, 0),
    plays: num(o, 'plays', where, 0),
    key: optStr(o, 'key', where),
    days: readDays(o.days, where),
  };
}

function readBucket(v: unknown): StoredBucket {
  const o = row(v, 'the listening');
  const w = 'a month of listening';
  const month = reqStr(o, 'month', w);
  if (!/^\d{4}-\d{2}$/.test(month)) fail(w, `has a month that is not YYYY-MM (${month})`);
  const tracks: TrackEntry[] = arr(o.tracks, w).map((t) => {
    const e = row(t, w);
    return {
      id: reqStr(e, 'id', w),
      title: e.title === undefined ? '' : reqStr(e, 'title', w),
      artist: e.artist === undefined ? '' : reqStr(e, 'artist', w),
      album: optStr(e, 'album', w),
      albumId: optStr(e, 'albumId', w),
      artistId: optStr(e, 'artistId', w),
      art: optStr(e, 'art', w),
      ms: num(e, 'ms', w, 0),
      plays: num(e, 'plays', w, 0),
      last: num(e, 'last', w, 0),
      days: readDays(e.days, w),
    };
  });
  const hours = arr(o.hours, w).map((h) => (typeof h === 'number' && Number.isFinite(h) ? h : fail(w, 'has hours that are not numbers')));
  const days: Record<number, number> = {};
  if (o.days !== undefined && o.days !== null) {
    if (!isObj(o.days)) fail(w, 'has days that are not an object');
    for (const [k, d] of Object.entries(o.days)) days[Number(k)] = typeof d === 'number' && Number.isFinite(d) ? d : fail(w, 'has a day that is not a number');
  }
  const optDays = (k: string) => (o[k] === undefined || o[k] === null ? null : readDays(o[k], w));
  return {
    version: num(o, 'version', w, 1),
    month,
    tracks,
    artists: arr(o.artists, w).map((a) => readName(a, w)),
    albums: arr(o.albums, w).map((a) => readName(a, w)),
    hours: hours.length === 24 ? hours : [...hours, ...new Array<number>(24).fill(0)].slice(0, 24),
    days,
    trackDays: optDays('trackDays'),
    artistDays: optDays('artistDays'),
    albumDays: optDays('albumDays'),
  };
}

function readSettings(v: unknown): Record<string, PrefValue> {
  if (v === undefined || v === null) return {};
  if (!isObj(v)) fail('the settings', 'are not an object');
  const out: Record<string, PrefValue> = {};
  for (const [k, p] of Object.entries(v)) {
    const o = row(p, 'a setting');
    out[k] = { type: reqStr(o, 'type', 'a setting'), value: optStr(o, 'value', 'a setting'), values: arr(o.values, 'a setting').map(String) };
  }
  return out;
}

/**
 * Reads a backup. Refuses what the phone refuses: not JSON, a missing or foreign `app`, a missing or newer
 * `version` (Android's defaults would let `{}` pass for a file that wipes the listening), a row of the wrong
 * shape. Nothing is applied here; [now] stands in for Kotlin's `System.currentTimeMillis()` defaults.
 */
export function parseBackup(text: string, now = Date.now()): BackupFile {
  let data: unknown;
  try {
    data = JSON.parse(text);
  } catch {
    throw new BackupError('That doesn’t look like an Auden backup.');
  }
  if (!isObj(data) || typeof data.app !== 'string') throw new BackupError('That doesn’t look like an Auden backup.');
  if (data.app !== APP_TAG && data.app !== LEGACY_APP_TAG) throw new BackupError('That backup is from another app.');
  if (typeof data.version !== 'number' || !Number.isInteger(data.version) || data.version < 1) throw new BackupError('That doesn’t look like an Auden backup.');
  if (data.version > SCHEMA_VERSION) throw new BackupError('That backup was written by a newer version of Auden.');

  const lib = data.library;
  const library: LibraryData | null =
    lib === undefined || lib === null
      ? null
      : isObj(lib)
        ? {
            tracks: arr(lib.tracks, 'the songs').map((t) => readTrack(t, now)),
            playlists: arr(lib.playlists, 'the playlists').map((p) => readPlaylist(p, now)),
            entries: arr(lib.entries, 'the playlist entries').map(readEntry),
            lyricsVariants: arr(lib.lyricsVariants, 'the saved lyrics').map((v) => readVariant(v, now)),
            lyricsPrefs: arr(lib.lyricsPrefs, 'the lyrics choices').map(readPref),
            spotifyMatches: arr(lib.spotifyMatches, 'the Spotify matches').map((m) => readMatch(m, now)),
          }
        : fail('the library', 'is not an object');

  return {
    app: data.app,
    version: data.version,
    versionName: typeof data.versionName === 'string' ? data.versionName : '',
    exportedAt: typeof data.exportedAt === 'string' ? data.exportedAt : '',
    settings: readSettings(data.settings),
    listening: arr(data.listening, 'the listening').map(readBucket),
    library,
    ...(data.origin === 'web' && { origin: 'web' as const }),
    ...(typeof data.settingsCapturedAt === 'string' && { settingsCapturedAt: data.settingsCapturedAt }),
  };
}

/** The suggested file name, as Android names it. */
export function suggestedBackupName(at = new Date()): string {
  const pad = (n: number) => String(n).padStart(2, '0');
  return `auden-backup-${at.getFullYear()}-${pad(at.getMonth() + 1)}-${pad(at.getDate())}.json`;
}
