import { openDB, type DBSchema, type IDBPDatabase } from 'idb';

/**
 * Auden's library database: AudenDb (Android, Room v8) in IndexedDB. Row shapes keep the Kotlin
 * entities' field names (Entities.kt) so a backup moves between the phone and the web without
 * translation; only values differ where the platforms must (a web file's `localUri` is an
 * `auden-file://` address, never a `content://` one). Web-only data (folder handles, cover
 * blobs…) goes in stores added by later versions, not in these rows.
 *
 * Booleans are not valid IndexedDB keys, so nothing here indexes `pinned` or `isVideo`.
 */

export const TrackOrigin = { YTM: 'YTM', LOCAL: 'LOCAL', DOWNLOAD: 'DOWNLOAD', SPOTIFY: 'SPOTIFY' } as const;
export type TrackOriginValue = (typeof TrackOrigin)[keyof typeof TrackOrigin];

export interface TrackRow {
  id?: number;
  videoId: string | null;
  spotifyId: string | null;
  isrc: string | null;
  /** Android: a content:// uri. Web: `auden-file://<rootId>/<relPath>`. Unique when set. */
  localUri: string | null;
  path: string | null;
  title: string;
  artist: string;
  artistId: string | null;
  album: string | null;
  albumId: string | null;
  thumbnailUrl: string | null;
  durationMs: number | null;
  durationText: string | null;
  trackNumber: number | null;
  discNumber: number | null;
  albumArtist: string | null;
  genre: string | null;
  year: number | null;
  folder: string | null;
  sizeBytes: number | null;
  modifiedAt: number | null;
  isVideo: boolean;
  origin: string;
  addedAt: number;
  identStatus: string | null;
  identSource: string | null;
  suggestedVideoId: string | null;
  suggestedTitle: string | null;
  suggestedArtist: string | null;
  identifiedAt: number | null;
  /**
   * Web only. A song's file as the PHONE knows it (`content://` uri, absolute path, folder, size, mtime),
   * carried here by a backup restore so it can be written back unchanged when the web exports again.
   * Kept out of `localUri`: everything on the web that sees a `localUri` takes it for a file in a browser folder.
   */
  androidFile?: { localUri: string | null; path: string | null; folder: string | null; sizeBytes: number | null; modifiedAt: number | null };
}

export interface PlaylistRow {
  /** Android's format: `local:playlist:<id>`. */
  id: string;
  title: string;
  createdAt: number;
  updatedAt: number;
  pinned: boolean;
  sourceId: string | null;
  coverUrl: string | null;
}

export interface PlaylistEntryRow {
  entryId?: number;
  playlistId: string;
  trackId: number;
  position: number;
  /**
   * Web only. The Spotify track id whose sync put this entry here. A sync may remove an entry only when it
   * carries one that the Spotify original no longer lists; a hand-added entry never does. (Android infers
   * "hand-added" from the shared track row's spotifyId, which a later sync can set on it.)
   */
  fromSpotifyId?: string;
}

export interface LyricsVariantRow {
  /** The video id, or the file's uri for a local song. */
  songKey: string;
  provider: string;
  format: string;
  content: string;
  fetchedAt: number;
}

export interface SongLyricsPrefRow {
  songKey: string;
  activeProvider: string | null;
  offsetMs: number | null;
}

/** Declared for parity with Room; Android never writes it (listening lives in `listening`). */
export interface PlayEventRow {
  id?: number;
  trackId: number;
  startedAt: number;
  listenedMs: number;
  completed: boolean;
}

export interface SpotifyMatchRow {
  spotifyId: string;
  status: string;
  /** Android's serialized `Song`; opaque until Spotify matching needs it (Phase 6). */
  songJson: string | null;
  matchedAt: number;
}

export interface DayStats {
  ms: number;
  plays: number;
}

export interface TrackEntry {
  id: string;
  title: string;
  artist: string;
  album: string | null;
  albumId: string | null;
  artistId: string | null;
  art: string | null;
  ms: number;
  plays: number;
  last: number;
  days: Record<string, DayStats>;
}

export interface NameEntry {
  name: string;
  sub: string | null;
  art: string | null;
  id: string | null;
  ms: number;
  plays: number;
  key: string | null;
  days: Record<string, DayStats>;
}

/** ListeningStats.kt's StoredBucket, one per month (`month` = "2026-11"); the Backup's `listening`. */
export interface StoredBucket {
  version: number;
  month: string;
  tracks: TrackEntry[];
  artists: NameEntry[];
  albums: NameEntry[];
  hours: number[];
  days: Record<number, number>;
  trackDays: Record<string, DayStats> | null;
  artistDays: Record<string, DayStats> | null;
  albumDays: Record<string, DayStats> | null;
}

export interface AudenDBSchema extends DBSchema {
  track: {
    key: number;
    value: TrackRow;
    indexes: { videoId: string; spotifyId: string; isrc: string; localUri: string; path: string };
  };
  playlist: { key: string; value: PlaylistRow };
  playlist_entry: {
    key: number;
    value: PlaylistEntryRow;
    indexes: { playlist_position: [string, number]; trackId: number };
  };
  lyrics_variant: { key: [string, string]; value: LyricsVariantRow };
  song_lyrics_pref: { key: string; value: SongLyricsPrefRow };
  play_event: { key: number; value: PlayEventRow; indexes: { trackId: number; startedAt: number } };
  spotify_match: { key: string; value: SpotifyMatchRow };
  listening: { key: string; value: StoredBucket };
  library_root: { key: string; value: LibraryRootRow };
  local_art: { key: string; value: LocalArtRow };
}

/**
 * A folder the user added to the local library (web only; Android reads MediaStore).
 * Chromium keeps the directory handle (structured-cloneable) and re-asks permission per session;
 * Firefox has no handles, so a 'files' root is only a name plus the index it left behind.
 */
export interface LibraryRootRow {
  id: string;
  name: string;
  kind: 'fsa' | 'files';
  handle?: FileSystemDirectoryHandle;
  addedAt: number;
  scannedAt: number | null;
}

/** A small cover (<= 256 px) shared by the tracks of an album or folder. Bytes, not a Blob: they clone everywhere. */
export interface LocalArtRow {
  key: string;
  bytes: ArrayBuffer;
  mime: string;
}

export type AudenDb = IDBPDatabase<AudenDBSchema>;
export type StoreName = keyof AudenDBSchema;

export const DB_NAME = 'auden-db';
export const DB_VERSION = 2;

/** The schema, version by version. Each version is an `if (oldVersion < n)` step, run in order; tested from frozen v1 data. */
export function upgradeAudenDb(db: IDBPDatabase<AudenDBSchema>, oldVersion: number): void {
  if (oldVersion < 1) {
    {
      const track = db.createObjectStore('track', { keyPath: 'id', autoIncrement: true });
      track.createIndex('videoId', 'videoId');
      track.createIndex('spotifyId', 'spotifyId');
      track.createIndex('isrc', 'isrc');
      // A null localUri is not a valid key, so those rows stay out of the index.
      track.createIndex('localUri', 'localUri', { unique: true });
      track.createIndex('path', 'path');
      db.createObjectStore('playlist', { keyPath: 'id' });
      const entry = db.createObjectStore('playlist_entry', { keyPath: 'entryId', autoIncrement: true });
      // Not unique: a reorder briefly holds two entries at one position.
      entry.createIndex('playlist_position', ['playlistId', 'position']);
      entry.createIndex('trackId', 'trackId');
      db.createObjectStore('lyrics_variant', { keyPath: ['songKey', 'provider'] });
      db.createObjectStore('song_lyrics_pref', { keyPath: 'songKey' });
      const play = db.createObjectStore('play_event', { keyPath: 'id', autoIncrement: true });
      play.createIndex('trackId', 'trackId');
      play.createIndex('startedAt', 'startedAt');
      db.createObjectStore('spotify_match', { keyPath: 'spotifyId' });
      db.createObjectStore('listening', { keyPath: 'month' });
    }
  }
  if (oldVersion < 2) {
    // Phase 2: the local library's folders and covers.
    db.createObjectStore('library_root', { keyPath: 'id' });
    db.createObjectStore('local_art', { keyPath: 'key' });
  }
}

/** Called when another tab needs this database to upgrade; the page should offer a reload. */
type Listener = () => void;
const blockedListeners = new Set<Listener>();
export function onDbBlocked(listener: Listener): () => void {
  blockedListeners.add(listener);
  return () => blockedListeners.delete(listener);
}

const opened = new Map<string, Promise<AudenDb>>();

/** The database, opened once per name. Later versions extend the `switch` and fall through. */
export function openAudenDb(name = DB_NAME): Promise<AudenDb> {
  let pending = opened.get(name);
  if (!pending) {
    pending = openDB<AudenDBSchema>(name, DB_VERSION, {
      upgrade: upgradeAudenDb,
      blocked() {
        blockedListeners.forEach((l) => l());
      },
      blocking() {
        // A newer version wants in: let go so its upgrade can run, and reopen on next use.
        opened.delete(name);
        void pending?.then((db) => db.close());
        blockedListeners.forEach((l) => l());
      },
      terminated() {
        opened.delete(name);
      },
    });
    opened.set(name, pending);
    // A failed open must not be remembered: every later call would reject until the page reloads.
    // The rejection still reaches whoever asked. A stale tab hits VersionError once another tab has
    // upgraded, so the app subscribes to onDbBlocked and offers a reload (shell/DbReloadBanner).
    const failed = pending;
    failed.catch(() => {
      if (opened.get(name) === failed) opened.delete(name);
    });
  }
  return pending;
}

/** Test seam: forgets (and closes) a cached connection. */
export async function closeAudenDb(name = DB_NAME): Promise<void> {
  const pending = opened.get(name);
  opened.delete(name);
  if (pending) (await pending).close();
}
