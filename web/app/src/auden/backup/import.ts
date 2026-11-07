import type { StoreNames } from 'idb';
import { queryClient } from '@/lib/queryClient';
import { notifyStores } from '../db/notify';
import { openAudenDb, type AudenDb, type AudenDBSchema, type PlaylistRow, type StoreName, type StoredBucket, type TrackRow } from '../db/schema';
import { artKeyFromUrl } from '../library/scan/diff';
import { discardPendingListening } from '../stats/listening';
import { BackupError, LEGACY_APP_TAG, type BackupEntry, type BackupFile, type LibraryData } from './format';
import { holdSettings } from './passthrough';

/**
 * Restores a backup (Android's BackupManager.import). The library is REPLACED, not merged: the songs, playlists,
 * lyrics and Spotify matches in the file become the library. Only what is this browser's own survives, because
 * no backup can carry it: the files in its folders (their rows keep their ids, so `auden:local:<id>` stays valid),
 * the lyrics saved under those files' `auden-file://` uris, the folders themselves and the covers still in use.
 * The listening is replaced too. A BitChord file, or one with no library, replaces only the listening.
 *
 * Everything that can be worked out from the file alone is worked out first. Then ONE readwrite transaction reads
 * the browser's file rows and covers, plans against them (synchronously) and writes; nothing but IndexedDB
 * requests is awaited inside it. Any failure aborts it, so a failed restore leaves every store as it was.
 */

const WEB_FILE = 'auden-file://';
/** The first string after every `auden-file://…` one ('/' + 1 = '0'): `[WEB_FILE, WEB_FILE_END)` is exactly the web uris. */
const WEB_FILE_END = 'auden-file:/0';
/** Autosync's Web Lock: held for the restore, so no tab starts syncing saved copies into a library being replaced. */
const AUTOSYNC_LOCK = 'auden-autosync';
const LOCK_WAIT_MS = 20_000;

type Store = StoreNames<AudenDBSchema>;

const LIBRARY_STORES = [
  'track',
  'playlist',
  'playlist_entry',
  'lyrics_variant',
  'song_lyrics_pref',
  'spotify_match',
  'listening',
  'play_event',
  'local_art',
] as const satisfies readonly Store[];

export interface ImportCounts {
  songs: number;
  playlists: number;
  entries: number;
  lyrics: number;
  matches: number;
  months: number;
}

export interface ImportPreview {
  /** 'library': the library and the listening are replaced. 'listening': only the listening is (BitChord, or no library). */
  replaces: 'library' | 'listening';
  bitchord: boolean;
  fromWeb: boolean;
  exportedAt: string;
  versionName: string;
  /** What the file holds. */
  inFile: ImportCounts;
  /** What this browser holds now (all of it is replaced, except what `kept` lists). */
  current: ImportCounts;
  /** What the restore will write (the file's, less what cannot be restored). For 'listening', only `months`. */
  willWrite: ImportCounts;
  /** Playlist entries whose song or playlist is not in the file (or was dropped): left out. */
  entriesDropped: number;
  /** Songs left out: a file in another browser's folder with no YouTube id, or a second row under one id. */
  songsDropped: number;
  /** Songs that named a file another song already had: the file is cleared on the later ones. */
  duplicateFilesCleared: number;
  /** Songs whose file is on the phone (`content://`): kept aside for the next export, not playable here. */
  androidFiles: number;
  /** This browser's own data that the restore keeps. */
  kept: {
    /** Songs that are files in this browser's folders (all kept, ids unchanged). */
    webFiles: number;
    /** Of those, how many take over a song from the file with the same YouTube id (its playlists, its details). */
    relinked: number;
    /** Lyrics (and lyrics choices) saved under a browser file's uri. */
    webLyrics: number;
    folders: number;
  };
  /** The phone's settings in the file: held to hand back on export, never applied here. */
  settings: number;
}

export interface ImportSummary {
  bitchord: boolean;
  replacedLibrary: boolean;
  songs: number;
  playlists: number;
  entries: number;
  lyrics: number;
  lyricsChoices: number;
  matches: number;
  months: number;
  entriesDropped: number;
  songsDropped: number;
  duplicateFilesCleared: number;
  androidRowsStashed: number;
  webFilesKept: number;
  relinked: number;
  orphanArtRemoved: number;
}

// ---- Planning (pure) ------------------------------------------------------------------------------------------

type FileFields = NonNullable<TrackRow['androidFile']>;
const FILE_FIELDS = ['localUri', 'path', 'folder', 'sizeBytes', 'modifiedAt'] as const;
const noFile = { localUri: null, path: null, folder: null, sizeBytes: null, modifiedAt: null } satisfies FileFields;

interface PreparedTrack {
  oldId: number;
  /** No id: the generator gives it one (or a kept file row lends it its own). */
  row: Omit<TrackRow, 'id'>;
}

interface PreparedEntry {
  playlistId: string;
  oldTrackId: number;
  position: number;
  fromSpotifyId?: string;
}

interface Prepared {
  tracks: PreparedTrack[];
  playlists: PlaylistRow[];
  entries: PreparedEntry[];
  lyricsVariants: LibraryData['lyricsVariants'];
  lyricsPrefs: LibraryData['lyricsPrefs'];
  spotifyMatches: LibraryData['spotifyMatches'];
  entriesDropped: number;
  songsDropped: number;
  duplicateFilesCleared: number;
  androidRowsStashed: number;
}

const lastById = <T>(rows: readonly T[], key: (row: T) => string): T[] => [...new Map(rows.map((r) => [key(r), r] as const)).values()];

/** The file's months, one bucket each (a later one wins, as a put would). */
const monthsOf = (file: BackupFile): StoredBucket[] => lastById(file.listening, (b) => b.month);

/**
 * Everything the file alone decides. A song's file that is not this browser's cannot be opened here:
 * - one in a browser folder (`auden-file://`, another browser's) is dropped when the song has no YouTube id, else forgotten;
 * - one on the phone (`content://`, a path…) is kept aside in `androidFile` and cleared on the row, so no web code takes
 *   it for a browser file; the export writes it back. Two songs naming one file (Room's unique index) keep it on the first.
 * Entries keep their order, renumbered from 0 per playlist; those whose song or playlist is missing are dropped.
 */
function prepareLibrary(lib: LibraryData): Prepared {
  const tracks: PreparedTrack[] = [];
  const ids = new Set<number>();
  const seenFiles = new Set<string>();
  let songsDropped = 0;
  let duplicateFilesCleared = 0;
  let androidRowsStashed = 0;

  for (const t of lib.tracks) {
    if (ids.has(t.id)) {
      songsDropped++;
      continue;
    }
    const { id: oldId, androidFile: _ignored, ...rest } = t;
    void _ignored;
    let row: Omit<TrackRow, 'id'> = rest;
    const uri = row.localUri;
    if (uri?.startsWith(WEB_FILE)) {
      if (!row.videoId) {
        songsDropped++;
        continue;
      }
      row = { ...row, ...noFile, origin: row.origin === 'LOCAL' ? 'YTM' : row.origin };
    } else if (FILE_FIELDS.some((k) => row[k] !== null && row[k] !== undefined)) {
      if (uri && seenFiles.has(uri)) {
        duplicateFilesCleared++;
        row = { ...row, ...noFile };
      } else {
        if (uri) seenFiles.add(uri);
        const stash: FileFields = { localUri: row.localUri, path: row.path, folder: row.folder, sizeBytes: row.sizeBytes, modifiedAt: row.modifiedAt };
        row = { ...row, ...noFile, androidFile: stash };
        androidRowsStashed++;
      }
    }
    ids.add(oldId);
    tracks.push({ oldId, row });
  }

  const playlists = lastById(lib.playlists, (p) => p.id);
  const playlistIds = new Set(playlists.map((p) => p.id));
  const byPlaylist = new Map<string, BackupEntry[]>();
  let entriesDropped = 0;
  for (const e of lib.entries) {
    if (!playlistIds.has(e.playlistId) || !ids.has(e.trackId)) {
      entriesDropped++;
      continue;
    }
    const list = byPlaylist.get(e.playlistId);
    if (list) list.push(e);
    else byPlaylist.set(e.playlistId, [e]);
  }
  const entries: PreparedEntry[] = [];
  for (const [playlistId, list] of byPlaylist) {
    list
      .sort((a, b) => a.position - b.position || a.entryId - b.entryId)
      .forEach((e, position) => entries.push({ playlistId, oldTrackId: e.trackId, position, ...(e.fromSpotifyId && { fromSpotifyId: e.fromSpotifyId }) }));
  }

  return {
    tracks,
    playlists,
    entries,
    lyricsVariants: lastById(lib.lyricsVariants, (v) => `${v.songKey}\u0000${v.provider}`),
    lyricsPrefs: lastById(lib.lyricsPrefs, (p) => p.songKey),
    spotifyMatches: lastById(lib.spotifyMatches, (m) => m.spotifyId),
    entriesDropped,
    songsDropped,
    duplicateFilesCleared,
    androidRowsStashed,
  };
}

interface TrackPlan {
  /** Browser file rows kept as they are. */
  kept: TrackRow[];
  /** A song from the file that takes over a browser file row (and its id). */
  merged: Array<{ oldId: number; row: TrackRow & { id: number } }>;
  /** Songs from the file that get a fresh id. */
  added: PreparedTrack[];
  orphanArt: string[];
}

/**
 * Plans the song rows against this browser's file rows. A file row with a YouTube id that a song in the file has
 * too (and that song brings no file of its own) is the same song: as LibraryIndexer links a file to the streaming
 * row, the song's details go onto the file row, which keeps its id (so `auden:local:<id>` and its cover stay
 * valid; its lyrics key is the video id either way). Covers no song, playlist or month of listening points at go.
 */
function planTracks(prep: Prepared, webRows: readonly TrackRow[], artKeys: readonly string[], listening: readonly StoredBucket[], playlists: readonly PlaylistRow[]): TrackPlan {
  const byVideo = new Map<string, TrackRow>();
  for (const w of [...webRows].sort((a, b) => (a.id ?? 0) - (b.id ?? 0))) if (w.videoId && !byVideo.has(w.videoId)) byVideo.set(w.videoId, w);
  const taken = new Set<number>();
  const merged: TrackPlan['merged'] = [];
  const added: PreparedTrack[] = [];
  for (const t of prep.tracks) {
    const w = t.row.videoId && !t.row.androidFile ? byVideo.get(t.row.videoId) : undefined;
    if (w?.id !== undefined && !taken.has(w.id)) {
      taken.add(w.id);
      const { androidFile: _none, ...song } = t.row;
      void _none;
      merged.push({
        oldId: t.oldId,
        row: {
          ...song,
          id: w.id,
          localUri: w.localUri,
          path: w.path,
          folder: w.folder,
          sizeBytes: w.sizeBytes,
          modifiedAt: w.modifiedAt,
          thumbnailUrl: song.thumbnailUrl ?? w.thumbnailUrl,
        },
      });
    } else {
      added.push(t);
    }
  }
  const kept = webRows.filter((w) => w.id !== undefined && !taken.has(w.id));

  const used = new Set<string>();
  const use = (url: string | null | undefined) => {
    const key = artKeyFromUrl(url);
    if (key !== null) used.add(key);
  };
  for (const r of kept) use(r.thumbnailUrl);
  for (const m of merged) use(m.row.thumbnailUrl);
  for (const a of added) use(a.row.thumbnailUrl);
  for (const p of playlists) use(p.coverUrl);
  for (const b of listening) for (const e of [...b.tracks, ...b.artists, ...b.albums]) use(e.art);
  return { kept, merged, added, orphanArt: artKeys.filter((k) => !used.has(k)) };
}

// ---- Reading the current state --------------------------------------------------------------------------------

const webUris = () => IDBKeyRange.bound(WEB_FILE, WEB_FILE_END, false, true);

export const isBitChord = (file: BackupFile): boolean => file.app === LEGACY_APP_TAG;
/** A BitChord file's library (if one ever had any) is not the phone's to restore: Android reads only its listening. */
const libraryOf = (file: BackupFile): LibraryData | null => (isBitChord(file) ? null : file.library);

/** What a restore of [file] would do here, read at one instant. Nothing is written. */
export async function previewImport(file: BackupFile): Promise<ImportPreview> {
  const lib = libraryOf(file);
  const db = await openAudenDb();
  const tx = db.transaction(['track', 'playlist', 'playlist_entry', 'lyrics_variant', 'song_lyrics_pref', 'spotify_match', 'listening', 'library_root'], 'readonly');
  const [songs, playlists, entries, lyrics, matches, months, webRows, webVariants, webPrefs, folders] = await Promise.all([
    tx.objectStore('track').count(),
    tx.objectStore('playlist').count(),
    tx.objectStore('playlist_entry').count(),
    tx.objectStore('lyrics_variant').count(),
    tx.objectStore('spotify_match').count(),
    tx.objectStore('listening').count(),
    tx.objectStore('track').index('localUri').getAll(webUris()),
    tx.objectStore('lyrics_variant').count(IDBKeyRange.bound([WEB_FILE], [WEB_FILE_END], false, true)),
    tx.objectStore('song_lyrics_pref').count(webUris()),
    tx.objectStore('library_root').count(),
    tx.done,
  ]);
  const listening = monthsOf(file);
  const prep = lib ? prepareLibrary(lib) : null;
  const plan = prep ? planTracks(prep, webRows, [], listening, prep.playlists) : null;
  return {
    replaces: lib ? 'library' : 'listening',
    bitchord: isBitChord(file),
    fromWeb: file.origin === 'web',
    exportedAt: file.exportedAt,
    versionName: file.versionName,
    inFile: {
      songs: lib?.tracks.length ?? 0,
      playlists: lib?.playlists.length ?? 0,
      entries: lib?.entries.length ?? 0,
      lyrics: lib?.lyricsVariants.length ?? 0,
      matches: lib?.spotifyMatches.length ?? 0,
      months: file.listening.length,
    },
    current: { songs, playlists, entries, lyrics, matches, months },
    willWrite: {
      songs: prep?.tracks.length ?? 0,
      playlists: prep?.playlists.length ?? 0,
      entries: prep?.entries.length ?? 0,
      lyrics: prep?.lyricsVariants.length ?? 0,
      matches: prep?.spotifyMatches.length ?? 0,
      months: listening.length,
    },
    entriesDropped: prep?.entriesDropped ?? 0,
    songsDropped: prep?.songsDropped ?? 0,
    duplicateFilesCleared: prep?.duplicateFilesCleared ?? 0,
    androidFiles: prep?.androidRowsStashed ?? 0,
    kept: { webFiles: webRows.length, relinked: plan?.merged.length ?? 0, webLyrics: webVariants + webPrefs, folders },
    settings: Object.keys(file.settings).length,
  };
}

// ---- Writing --------------------------------------------------------------------------------------------------

let restoring = false;
/** Whether a restore is running in this tab (the UI disables its button; nothing else needs to check). */
export const isRestoring = (): boolean => restoring;

/**
 * Restores [file] (already read by parseBackup). Throws, having changed nothing, if anything fails. Only after the
 * commit are the phone's settings held for the next export and the app told what changed. NOTHING from
 * `file.settings` is applied here. What stays stale in memory afterwards (the queue, an open playlist…) is the
 * caller's to refresh: see the Settings screen, which offers a reload.
 */
export async function importBackup(file: BackupFile): Promise<ImportSummary> {
  if (restoring) throw new BackupError('A restore is already running.');
  restoring = true;
  try {
    const lib = libraryOf(file);
    const prep = lib ? prepareLibrary(lib) : null;
    const listening = monthsOf(file);
    const summary = await withAutosyncHeld(async () => {
      const db = await openAudenDb();
      return prep ? writeLibrary(db, prep, listening) : writeListening(db, listening);
    });
    // Committed: only now may anything outside the database change.
    holdSettings(file);
    const touched: StoreName[] = prep ? [...LIBRARY_STORES] : ['listening'];
    notifyStores(...touched);
    void queryClient.invalidateQueries({ queryKey: ['auden'] });
    return { ...summary, bitchord: isBitChord(file) };
  } finally {
    restoring = false;
  }
}

const noop = () => {};

type Tx = ReturnType<typeof openTx>;
function openTx(db: AudenDb, stores: readonly Store[]) {
  // The listening recorder's batches from before this instant are dropped, so none is merged into what this
  // transaction writes (a flush that already opened its own transaction is ordered before this one and replaced).
  discardPendingListening();
  return db.transaction(stores as Store[], 'readwrite', { durability: 'strict' });
}

/**
 * Runs [body] in [tx] and commits, or aborts. A put() can throw synchronously (a value that will not clone, a bad
 * key); that does NOT abort the transaction, and the clears already issued would commit. So any throw aborts it here.
 * Every request's promise is tracked and handled, so a failure is reported once, through the abort.
 */
async function run<T>(tx: Tx, body: (req: <R>(p: Promise<R>) => Promise<R>) => Promise<T>): Promise<T> {
  const done = tx.done;
  done.catch(noop);
  const pending: Promise<unknown>[] = [];
  const req = <R>(p: Promise<R>): Promise<R> => {
    p.catch(noop);
    pending.push(p);
    return p;
  };
  try {
    const result = await body(req);
    await Promise.all([...pending, done]);
    return result;
  } catch (error) {
    try {
      tx.abort();
    } catch {
      // Already aborted (a failed request aborts it) or finished.
    }
    await done.catch(noop);
    throw error;
  }
}

type Written = Omit<ImportSummary, 'bitchord'>;

function writeListening(db: AudenDb, listening: StoredBucket[]): Promise<Written> {
  const tx = openTx(db, ['listening']);
  return run(tx, async (req) => {
    const store = tx.objectStore('listening');
    req(store.clear());
    for (const b of listening) req(store.put(b));
    return {
      ...zero,
      replacedLibrary: false,
      months: listening.length,
    };
  });
}

const zero: Written = {
  replacedLibrary: false,
  songs: 0,
  playlists: 0,
  entries: 0,
  lyrics: 0,
  lyricsChoices: 0,
  matches: 0,
  months: 0,
  entriesDropped: 0,
  songsDropped: 0,
  duplicateFilesCleared: 0,
  androidRowsStashed: 0,
  webFilesKept: 0,
  relinked: 0,
  orphanArtRemoved: 0,
};

function writeLibrary(db: AudenDb, prep: Prepared, listening: StoredBucket[]): Promise<Written> {
  const tx = openTx(db, LIBRARY_STORES);
  return run(tx, async (req) => {
    const track = tx.objectStore('track');
    // Read inside the transaction, so a scan writing between a read and this restore cannot be lost or undone.
    const [webRows, artKeys] = await Promise.all([req(track.index('localUri').getAll(webUris())), req(tx.objectStore('local_art').getAllKeys())]);
    const plan = planTracks(prep, webRows, artKeys, listening, prep.playlists);

    // Replace. Range deletes leave the lyrics saved under a browser file's uri; the clears take the rest.
    req(track.clear());
    for (const name of ['playlist', 'playlist_entry', 'spotify_match', 'listening', 'play_event'] as const) req(tx.objectStore(name).clear());
    const variants = tx.objectStore('lyrics_variant');
    req(variants.delete(IDBKeyRange.upperBound([WEB_FILE], true)));
    req(variants.delete(IDBKeyRange.lowerBound([WEB_FILE_END])));
    const prefs = tx.objectStore('song_lyrics_pref');
    req(prefs.delete(IDBKeyRange.upperBound(WEB_FILE, true)));
    req(prefs.delete(IDBKeyRange.lowerBound(WEB_FILE_END)));
    const art = tx.objectStore('local_art');
    for (const key of plan.orphanArt) req(art.delete(key));

    // Songs: the browser's file rows back under their ids, then the file's songs under fresh ones.
    const newId = new Map<number, number>();
    for (const row of plan.kept) req(track.put(row));
    for (const m of plan.merged) {
      req(track.put(m.row));
      newId.set(m.oldId, m.row.id);
    }
    const fresh = await Promise.all(plan.added.map((t) => req(track.add(t.row as TrackRow))));
    plan.added.forEach((t, i) => newId.set(t.oldId, fresh[i]!));

    const playlist = tx.objectStore('playlist');
    for (const p of prep.playlists) req(playlist.put(p));
    const entry = tx.objectStore('playlist_entry');
    for (const e of prep.entries) {
      req(entry.add({ playlistId: e.playlistId, trackId: newId.get(e.oldTrackId)!, position: e.position, ...(e.fromSpotifyId && { fromSpotifyId: e.fromSpotifyId }) }));
    }
    for (const v of prep.lyricsVariants) req(variants.put(v));
    for (const p of prep.lyricsPrefs) req(prefs.put(p));
    const matches = tx.objectStore('spotify_match');
    for (const m of prep.spotifyMatches) req(matches.put(m));
    const months = tx.objectStore('listening');
    for (const b of listening) req(months.put(b));

    return {
      replacedLibrary: true,
      songs: prep.tracks.length,
      playlists: prep.playlists.length,
      entries: prep.entries.length,
      lyrics: prep.lyricsVariants.length,
      lyricsChoices: prep.lyricsPrefs.length,
      matches: prep.spotifyMatches.length,
      months: listening.length,
      entriesDropped: prep.entriesDropped,
      songsDropped: prep.songsDropped,
      duplicateFilesCleared: prep.duplicateFilesCleared,
      androidRowsStashed: prep.androidRowsStashed,
      webFilesKept: webRows.length,
      relinked: plan.merged.length,
      orphanArtRemoved: plan.orphanArt.length,
    };
  });
}

/**
 * Holds autosync's lock (every tab's) for the restore: a sync asks for it with `ifAvailable`, so none starts meanwhile;
 * one already running is waited for, up to [LOCK_WAIT_MS]. The other jobs need no lock (each of their writes is one
 * transaction that re-reads what it changes): a scan finds a kept file row by its uri, identification only patches a
 * row that is still there and still unmatched, and a lyrics fetch at worst saves lyrics for a song that is gone.
 */
async function withAutosyncHeld<T>(fn: () => Promise<T>): Promise<T> {
  const locks = (typeof navigator !== 'undefined' ? navigator.locks : undefined) as LockManager | undefined;
  if (!locks?.request) return fn();
  const abort = new AbortController();
  const timer = setTimeout(() => abort.abort(), LOCK_WAIT_MS);
  let granted = false;
  try {
    return await locks.request(AUTOSYNC_LOCK, { signal: abort.signal }, () => {
      granted = true;
      clearTimeout(timer);
      return fn();
    });
  } catch (error) {
    if (!granted && abort.signal.aborted) throw new BackupError('Your saved playlists are being updated. Try again in a minute.');
    throw error;
  } finally {
    clearTimeout(timer);
  }
}
