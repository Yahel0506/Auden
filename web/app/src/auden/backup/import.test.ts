// @vitest-environment happy-dom
import 'fake-indexeddb/auto';
import { IDBFactory } from 'fake-indexeddb';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { newTrack } from '../db/dao';
import { localUriOf } from '../db/libraryDao';
import { closeAudenDb, openAudenDb, type StoredBucket, type TrackRow } from '../db/schema';
import { artUrlOf } from '../library/scan/diff';
import { record, type Pending } from '../stats/bucket';
import { flushListening, listeningGeneration } from '../stats/listening';
import { buildBackup, readSnapshot } from './export';
import { parseBackup, type BackupFile } from './format';
import { importBackup, previewImport } from './import';
import { readHeldSettings } from './passthrough';

/** Sees when the import hands the settings over; the real holdSettings still runs. */
let onHold: (() => void) | null = null;
vi.mock('./passthrough', async (importOriginal) => {
  const real = await importOriginal<typeof import('./passthrough')>();
  return { ...real, holdSettings: (...args: Parameters<typeof real.holdSettings>) => (onHold?.(), real.holdSettings(...args)) };
});

beforeEach(() => {
  globalThis.indexedDB = new IDBFactory();
  localStorage.clear();
  onHold = null;
});
afterEach(async () => {
  vi.restoreAllMocks();
  await closeAudenDb();
});

// ---- Helpers ---------------------------------------------------------------------------------------------------

const song = (id: number, over: Record<string, unknown> = {}) => ({ id, videoId: `vid${id}`, title: `Song ${id}`, artist: 'Artist', ...over });
const month = (m: string, trackId: string, ms: number) => ({ month: m, tracks: [{ id: trackId, title: trackId, artist: 'A', ms, plays: 1, last: 1 }] });

/** A backup as the phone writes it, read through the real parser so every default is Kotlin's. */
function backup(body: Record<string, unknown>): BackupFile {
  return parseBackup(JSON.stringify({ app: 'auden', version: 2, versionName: '1.11.0', exportedAt: '2026-11-01T10:00:00Z', ...body }), 1_700_000_000_000);
}

const ROOT = 'root1';
const webRow = (relPath: string, over: Partial<TrackRow> = {}): TrackRow =>
  newTrack({ title: relPath, artist: 'Unknown Artist', localUri: localUriOf(ROOT, relPath), path: relPath, folder: '', sizeBytes: 100, modifiedAt: 5, origin: 'LOCAL', ...over });

/** Every store, keys and values, as it is now. */
async function dump(): Promise<Record<string, { keys: unknown[]; values: unknown[] }>> {
  const db = await openAudenDb();
  const out: Record<string, { keys: unknown[]; values: unknown[] }> = {};
  for (const name of [...db.objectStoreNames].sort()) {
    const tx = db.transaction(name as 'track');
    out[name] = { keys: await tx.store.getAllKeys(), values: await tx.store.getAll() };
    await tx.done;
  }
  return out;
}

/** Playlist title → the songs in it, in order: the graph, whatever the ids. */
async function graph(): Promise<Record<string, Array<string | null>>> {
  const db = await openAudenDb();
  const [playlists, entries, tracks] = await Promise.all([db.getAll('playlist'), db.getAll('playlist_entry'), db.getAll('track')]);
  const byId = new Map(tracks.map((t) => [t.id!, t]));
  const out: Record<string, Array<string | null>> = {};
  for (const p of playlists) {
    out[p.title] = entries
      .filter((e) => e.playlistId === p.id)
      .sort((a, b) => a.position - b.position)
      .map((e) => byId.get(e.trackId)?.title ?? null);
  }
  return out;
}

/** A library already in the browser: two streams, a playlist, lyrics, a match, two months, a web file, covers. */
async function seed(): Promise<{ webId: number; plainWebId: number }> {
  const db = await openAudenDb();
  const tx = db.transaction(['track', 'playlist', 'playlist_entry', 'lyrics_variant', 'song_lyrics_pref', 'spotify_match', 'listening', 'play_event', 'library_root', 'local_art'], 'readwrite');
  const t = tx.objectStore('track');
  const a = await t.add(newTrack({ title: 'Old A', artist: 'X', videoId: 'oldA' }));
  await t.add(newTrack({ title: 'Old B', artist: 'X', videoId: 'oldB' }));
  const webId = await t.add(webRow('linked.mp3', { videoId: 'vidShared', thumbnailUrl: artUrlOf('album:kept') }));
  const plainWebId = await t.add(webRow('plain.mp3'));
  await tx.objectStore('playlist').put({ id: 'local:playlist:old', title: 'Old list', createdAt: 1, updatedAt: 1, pinned: false, sourceId: null, coverUrl: artUrlOf('playlist:local:playlist:old:abc') });
  await tx.objectStore('playlist_entry').add({ playlistId: 'local:playlist:old', trackId: a, position: 0 });
  await tx.objectStore('lyrics_variant').put({ songKey: 'oldA', provider: 'lrclib', format: 'LRC', content: 'old', fetchedAt: 1 });
  await tx.objectStore('lyrics_variant').put({ songKey: localUriOf(ROOT, 'plain.mp3'), provider: 'lrclib', format: 'LRC', content: 'web', fetchedAt: 1 });
  await tx.objectStore('song_lyrics_pref').put({ songKey: 'oldA', activeProvider: 'lrclib', offsetMs: null });
  await tx.objectStore('song_lyrics_pref').put({ songKey: localUriOf(ROOT, 'plain.mp3'), activeProvider: 'lrclib', offsetMs: 250 });
  await tx.objectStore('spotify_match').put({ spotifyId: 'spOld', status: 'AUTO', songJson: null, matchedAt: 1 });
  for (const m of ['2026-09', '2026-10']) await tx.objectStore('listening').put(parseBackup(JSON.stringify({ app: 'auden', version: 2, listening: [month(m, 'oldA', 999)] })).listening[0]!);
  await tx.objectStore('play_event').add({ trackId: a, startedAt: 1, listenedMs: 1, completed: true });
  await tx.objectStore('library_root').put({ id: ROOT, name: 'Music', kind: 'files', addedAt: 1, scannedAt: null });
  for (const key of ['album:kept', 'playlist:local:playlist:old:abc', 'folder:unused']) await tx.objectStore('local_art').put({ key, bytes: new Uint8Array([1, 2, 3]).buffer, mime: 'image/jpeg' });
  await tx.done;
  return { webId, plainWebId };
}

const phoneFile = () =>
  backup({
    settings: { theme: { type: 'string', value: 'dark' } },
    listening: [month('2026-10', 'vid10', 1000), month('2026-11', 'vid10', 2000)],
    library: {
      // Room ids with gaps, as a phone that deleted songs writes them.
      tracks: [song(1000), song(5), song(77, { videoId: null, title: 'Unavailable' })],
      playlists: [
        { id: 'sp_local_mix', title: 'Mix', createdAt: 10 },
        { id: 'local:playlist:web', title: 'Web made' },
      ],
      entries: [
        { entryId: 9, playlistId: 'sp_local_mix', trackId: 5, position: 4 },
        { entryId: 3, playlistId: 'sp_local_mix', trackId: 1000, position: 1 },
        { entryId: 4, playlistId: 'sp_local_mix', trackId: 77, position: 2, fromSpotifyId: 'sp77' },
        { entryId: 5, playlistId: 'local:playlist:web', trackId: 1000, position: 0 },
      ],
      lyricsVariants: [{ songKey: 'vid5', provider: 'lrclib', format: 'LRC', content: 'five' }],
      lyricsPrefs: [{ songKey: 'vid5', activeProvider: 'lrclib' }],
      spotifyMatches: [{ spotifyId: 'sp1', status: 'AUTO' }],
    },
  });

// ---- The library ----------------------------------------------------------------------------------------------

describe('importBackup: library', () => {
  it('replaces the library with fresh ids, and every entry still points at the right song in order', async () => {
    await seed();
    const summary = await importBackup(phoneFile());

    expect(await graph()).toEqual({ Mix: ['Song 1000', 'Unavailable', 'Song 5'], 'Web made': ['Song 1000'] });
    const db = await openAudenDb();
    const tracks = await db.getAll('track');
    // The old streams are gone; the file's songs got ids from the generator (past every id the store ever gave).
    expect(tracks.map((t) => t.title).sort()).toEqual(['Song 1000', 'Song 5', 'Unavailable', 'linked.mp3', 'plain.mp3']);
    const imported = tracks.filter((t) => t.title.startsWith('Song') || t.title === 'Unavailable');
    for (const t of imported) expect(t.id!).toBeGreaterThan(4);
    expect(imported.map((t) => t.id)).not.toContain(1000);
    expect(imported.find((t) => t.title === 'Unavailable')).toMatchObject({ videoId: null, localUri: null });

    const entries = await db.getAll('playlist_entry');
    expect(entries.filter((e) => e.playlistId === 'sp_local_mix').map((e) => e.position).sort()).toEqual([0, 1, 2]);
    expect(entries.find((e) => e.fromSpotifyId)).toMatchObject({ fromSpotifyId: 'sp77', position: 1 });
    expect(entries.map((e) => e.entryId)).not.toContain(9);
    // Playlists keep their ids, both forms.
    expect((await db.getAllKeys('playlist')).sort()).toEqual(['local:playlist:web', 'sp_local_mix']);
    expect(await db.getAll('spotify_match')).toEqual([{ spotifyId: 'sp1', status: 'AUTO', songJson: null, matchedAt: 1_700_000_000_000 }]);
    expect(await db.getAll('play_event')).toEqual([]);
    expect(summary).toMatchObject({ bitchord: false, replacedLibrary: true, songs: 3, playlists: 2, entries: 4, lyrics: 1, matches: 1, months: 2, entriesDropped: 0, webFilesKept: 2 });
  });

  it('round-trips through the web export: the same graph and listening come back', async () => {
    await importBackup(phoneFile());
    const before = await graph();
    const months = await (await openAudenDb()).getAll('listening');
    const { file } = buildBackup(await readSnapshot(), readHeldSettings());
    const text = JSON.stringify(file);

    globalThis.indexedDB = new IDBFactory();
    await closeAudenDb();
    await importBackup(parseBackup(text));
    expect(await graph()).toEqual(before);
    expect(await (await openAudenDb()).getAll('listening')).toEqual(months);
    expect(readHeldSettings()?.settings).toEqual({ theme: { type: 'string', value: 'dark', values: [] } });
  });

  it('replaces the listening, it does not merge it, and clears the plays', async () => {
    await seed();
    await importBackup(phoneFile());
    const months: StoredBucket[] = await (await openAudenDb()).getAll('listening');
    expect(months.map((m) => m.month)).toEqual(['2026-10', '2026-11']);
    expect(months[0]!.tracks).toEqual([expect.objectContaining({ id: 'vid10', ms: 1000 })]);
    expect(months.flatMap((m) => m.tracks.map((t) => t.id))).not.toContain('oldA');
  });

  it('keeps the browser files under their ids, their lyrics, the folders; an incoming lyric with the same key wins', async () => {
    const { plainWebId } = await seed();
    const webKey = localUriOf(ROOT, 'plain.mp3');
    const file = phoneFile();
    file.library!.lyricsPrefs.push({ songKey: webKey, activeProvider: 'genius', offsetMs: null });
    await importBackup(file);
    const db = await openAudenDb();
    expect(await db.get('track', plainWebId)).toMatchObject({ title: 'plain.mp3', localUri: webKey });
    expect(await db.get('lyrics_variant', [webKey, 'lrclib'])).toMatchObject({ content: 'web' });
    expect(await db.get('lyrics_variant', ['oldA', 'lrclib'])).toBeUndefined();
    expect(await db.get('lyrics_variant', ['vid5', 'lrclib'])).toMatchObject({ content: 'five' });
    expect(await db.get('song_lyrics_pref', webKey)).toEqual({ songKey: webKey, activeProvider: 'genius', offsetMs: null });
    expect(await db.get('song_lyrics_pref', 'oldA')).toBeUndefined();
    expect(await db.getAll('library_root')).toHaveLength(1);
  });

  it('links a kept browser file to the song with its YouTube id: the file row keeps its id and takes the song and its entries', async () => {
    const { webId } = await seed();
    const file = phoneFile();
    file.library!.tracks.push({ ...file.library!.tracks[0]!, id: 42, videoId: 'vidShared', title: 'Shared from phone', thumbnailUrl: null });
    file.library!.entries.push({ entryId: 50, playlistId: 'sp_local_mix', trackId: 42, position: 9 });
    const summary = await importBackup(file);
    const db = await openAudenDb();
    const row = await db.get('track', webId);
    expect(row).toMatchObject({ title: 'Shared from phone', videoId: 'vidShared', localUri: localUriOf(ROOT, 'linked.mp3'), path: 'linked.mp3', sizeBytes: 100, thumbnailUrl: artUrlOf('album:kept') });
    expect((await db.getAll('track')).filter((t) => t.videoId === 'vidShared')).toHaveLength(1);
    expect((await graph()).Mix).toEqual(['Song 1000', 'Unavailable', 'Song 5', 'Shared from phone']);
    expect((await db.getAll('playlist_entry')).some((e) => e.trackId === webId)).toBe(true);
    expect(summary).toMatchObject({ relinked: 1, webFilesKept: 2, songs: 4 });
  });

  it('does not link a browser file to a song that brings its own phone file', async () => {
    const { webId } = await seed();
    const file = phoneFile();
    file.library!.tracks.push({ ...file.library!.tracks[0]!, id: 42, videoId: 'vidShared', localUri: 'content://media/external/audio/media/9' });
    await importBackup(file);
    const db = await openAudenDb();
    expect(await db.get('track', webId)).toMatchObject({ title: 'linked.mp3' });
    expect((await db.getAll('track')).filter((t) => t.videoId === 'vidShared')).toHaveLength(2);
  });

  it('keeps a phone file aside: the row shows no file, the export writes it back unchanged', async () => {
    const file = phoneFile();
    file.library!.tracks[0] = { ...file.library!.tracks[0]!, localUri: 'content://media/external/audio/media/31', path: '/storage/emulated/0/Music/a.mp3', folder: 'Music', sizeBytes: 4000, modifiedAt: 99, origin: 'LOCAL' };
    const summary = await importBackup(file);
    const db = await openAudenDb();
    const row = (await db.getAll('track')).find((t) => t.title === 'Song 1000')!;
    expect(row).toMatchObject({ localUri: null, path: null, folder: null, sizeBytes: null, modifiedAt: null, origin: 'LOCAL' });
    expect(row.androidFile).toEqual({ localUri: 'content://media/external/audio/media/31', path: '/storage/emulated/0/Music/a.mp3', folder: 'Music', sizeBytes: 4000, modifiedAt: 99 });
    expect(await db.getAllKeysFromIndex('track', 'localUri')).toEqual([]);
    expect(summary.androidRowsStashed).toBe(1);

    const back = buildBackup(await readSnapshot(), null).file.library!.tracks.find((t) => t.title === 'Song 1000')!;
    expect(back).toMatchObject({ localUri: 'content://media/external/audio/media/31', path: '/storage/emulated/0/Music/a.mp3', sizeBytes: 4000 });
  });

  it('keeps a file only on the first song naming it', async () => {
    const file = phoneFile();
    const uri = 'content://media/external/audio/media/31';
    file.library!.tracks[0] = { ...file.library!.tracks[0]!, localUri: uri, path: '/a.mp3' };
    file.library!.tracks[1] = { ...file.library!.tracks[1]!, localUri: uri, path: '/a.mp3' };
    const summary = await importBackup(file);
    const rows = await (await openAudenDb()).getAll('track');
    expect(rows.find((t) => t.title === 'Song 1000')!.androidFile?.localUri).toBe(uri);
    expect(rows.find((t) => t.title === 'Song 5')).toMatchObject({ localUri: null, path: null });
    expect(rows.find((t) => t.title === 'Song 5')!.androidFile).toBeUndefined();
    expect(summary).toMatchObject({ duplicateFilesCleared: 1, androidRowsStashed: 1, songs: 3 });
  });

  it("forgets another browser's file: the song stays when it has a YouTube id, else it goes with its entries", async () => {
    const file = phoneFile();
    file.library!.tracks[0] = { ...file.library!.tracks[0]!, localUri: 'auden-file://otherRoot/x.mp3', path: 'x.mp3', origin: 'LOCAL' };
    file.library!.tracks[2] = { ...file.library!.tracks[2]!, localUri: 'auden-file://otherRoot/y.mp3', path: 'y.mp3' };
    const summary = await importBackup(file);
    const rows = await (await openAudenDb()).getAll('track');
    expect(rows.find((t) => t.title === 'Song 1000')).toMatchObject({ localUri: null, path: null, origin: 'YTM' });
    expect(rows.find((t) => t.title === 'Unavailable')).toBeUndefined();
    expect((await graph()).Mix).toEqual(['Song 1000', 'Song 5']);
    expect(summary).toMatchObject({ songsDropped: 1, entriesDropped: 1, songs: 2 });
  });

  it('drops entries whose song or playlist is not in the file, and counts them', async () => {
    const file = phoneFile();
    file.library!.entries.push({ entryId: 60, playlistId: 'sp_local_mix', trackId: 999, position: 0 }, { entryId: 61, playlistId: 'sp_local_gone', trackId: 5, position: 0 });
    const summary = await importBackup(file);
    expect(await graph()).toEqual({ Mix: ['Song 1000', 'Unavailable', 'Song 5'], 'Web made': ['Song 1000'] });
    expect((await (await openAudenDb()).getAll('playlist_entry')).every((e) => e.playlistId !== 'sp_local_gone')).toBe(true);
    expect(summary.entriesDropped).toBe(2);
  });

  it('removes the covers nothing points at any more, and keeps those still used', async () => {
    await seed();
    const summary = await importBackup(phoneFile());
    expect((await (await openAudenDb()).getAllKeys('local_art')).sort()).toEqual(['album:kept']);
    expect(summary.orphanArtRemoved).toBe(2);
  });

  it('keeps a cover the restored listening points at', async () => {
    await seed();
    const file = phoneFile();
    file.listening[0]!.albums.push({ name: 'Al', sub: null, art: artUrlOf('folder:unused'), id: null, ms: 1, plays: 1, key: null, days: {} });
    await importBackup(file);
    expect((await (await openAudenDb()).getAllKeys('local_art')).sort()).toEqual(['album:kept', 'folder:unused']);
  });
});

// ---- Only the listening ---------------------------------------------------------------------------------------

describe('importBackup: listening only', () => {
  it('a BitChord file replaces the listening and leaves every library store as it was', async () => {
    await seed();
    const before = await dump();
    const file = parseBackup(JSON.stringify({ app: 'bitchord', version: 1, settings: { a: { type: 'bool', value: 'true' } }, listening: [month('2025-01', 'bc', 5)] }));
    const summary = await importBackup(file);
    const after = await dump();
    for (const store of Object.keys(before)) if (store !== 'listening') expect(after[store], store).toEqual(before[store]);
    expect(after.listening!.keys).toEqual(['2025-01']);
    expect(summary).toMatchObject({ bitchord: true, replacedLibrary: false, months: 1, songs: 0 });
  });

  it('an Auden file with no library does the same', async () => {
    await seed();
    const before = await dump();
    await importBackup(backup({ listening: [month('2026-11', 'x', 1)] }));
    const after = await dump();
    expect(after.track).toEqual(before.track);
    expect(after.playlist_entry).toEqual(before.playlist_entry);
    expect(after.play_event).toEqual(before.play_event);
    expect(after.listening!.keys).toEqual(['2026-11']);
  });
});

// ---- Failure --------------------------------------------------------------------------------------------------

describe('importBackup: a failure changes nothing', () => {
  const held = { settings: { old: { type: 'string', value: 'kept' } }, capturedAt: 'then', versionName: '1.0' };

  it('ROLLBACK: a put() that throws synchronously after the clears leaves EVERY store exactly as it was', async () => {
    await seed();
    localStorage.setItem('auden:backup:androidSettings', JSON.stringify(held));
    const before = await dump();
    const put = IDBObjectStore.prototype.put;
    let calls = 0;
    vi.spyOn(IDBObjectStore.prototype, 'put').mockImplementation(function (this: IDBObjectStore, ...args: Parameters<IDBObjectStore['put']>) {
      // The first puts land (kept file rows); then one throws, as a value that will not clone would.
      if (++calls === 2) throw new DOMException('could not be cloned', 'DataCloneError');
      return put.apply(this, args);
    });
    await expect(importBackup(phoneFile())).rejects.toThrow('could not be cloned');
    vi.restoreAllMocks();
    expect(calls).toBe(2);
    expect(await dump()).toEqual(before);
    expect(readHeldSettings()).toEqual(held);
  });

  it('a failure later in the transaction (after its awaits) is rolled back too', async () => {
    await seed();
    localStorage.setItem('auden:backup:androidSettings', JSON.stringify(held));
    const before = await dump();
    const add = IDBObjectStore.prototype.add;
    vi.spyOn(IDBObjectStore.prototype, 'add').mockImplementation(function (this: IDBObjectStore, ...args: Parameters<IDBObjectStore['add']>) {
      if (this.name === 'playlist_entry') throw new DOMException('bad key', 'DataError');
      return add.apply(this, args);
    });
    await expect(importBackup(phoneFile())).rejects.toThrow('bad key');
    vi.restoreAllMocks();
    expect(await dump()).toEqual(before);
    expect(readHeldSettings()).toEqual(held);
  });

  it('holds the settings only once the transaction has committed', async () => {
    let committed = false;
    const transaction = IDBDatabase.prototype.transaction;
    vi.spyOn(IDBDatabase.prototype, 'transaction').mockImplementation(function (this: IDBDatabase, ...args: Parameters<IDBDatabase['transaction']>) {
      const tx = transaction.apply(this, args);
      if (args[1] === 'readwrite') tx.addEventListener('complete', () => (committed = true));
      return tx;
    });
    let heldWhileOpen: boolean | null = null;
    onHold = () => (heldWhileOpen = !committed);
    await importBackup(phoneFile());
    expect(heldWhileOpen).toBe(false);
    expect(readHeldSettings()).toMatchObject({ settings: { theme: { value: 'dark' } }, capturedAt: '2026-11-01T10:00:00Z', versionName: '1.11.0' });
  });
});

// ---- The recorder's pending listening -------------------------------------------------------------------------

describe('pending listening', () => {
  it('a batch taken before the restore is not merged into what it wrote', async () => {
    const pending: Pending = new Map();
    record(pending, { videoId: 'stale', title: 'S', artist: 'A', albumName: null, albumId: null, artistId: null, thumbnailUrl: null }, 5000, true, new Date(2026, 10, 2));
    const takenIn = listeningGeneration();
    await importBackup(phoneFile());
    await flushListening(pending, takenIn);
    const months = await (await openAudenDb()).getAll('listening');
    expect(months.flatMap((m) => m.tracks.map((t) => t.id))).not.toContain('stale');
    // One taken after it lands as usual.
    await flushListening(pending);
    expect((await (await openAudenDb()).getAll('listening')).flatMap((m) => m.tracks.map((t) => t.id))).toContain('stale');
  });
});

// ---- Preview --------------------------------------------------------------------------------------------------

describe('previewImport', () => {
  it('counts the file, the library it replaces and what stays, and writes nothing', async () => {
    await seed();
    const file = phoneFile();
    file.library!.tracks.push({ ...file.library!.tracks[0]!, id: 42, videoId: 'vidShared' }, { ...file.library!.tracks[0]!, id: 43, videoId: 'p', localUri: 'content://x/1' });
    file.library!.entries.push({ entryId: 70, playlistId: 'nope', trackId: 5, position: 0 });
    const before = await dump();
    const preview = await previewImport(file);
    expect(await dump()).toEqual(before);
    expect(preview).toEqual({
      replaces: 'library',
      bitchord: false,
      fromWeb: false,
      exportedAt: '2026-11-01T10:00:00Z',
      versionName: '1.11.0',
      inFile: { songs: 5, playlists: 2, entries: 5, lyrics: 1, matches: 1, months: 2 },
      current: { songs: 4, playlists: 1, entries: 1, lyrics: 2, matches: 1, months: 2 },
      willWrite: { songs: 5, playlists: 2, entries: 4, lyrics: 1, matches: 1, months: 2 },
      entriesDropped: 1,
      songsDropped: 0,
      duplicateFilesCleared: 0,
      androidFiles: 1,
      kept: { webFiles: 2, relinked: 1, webLyrics: 2, folders: 1 },
      settings: 1,
    });
  });

  it('says a BitChord file only replaces the listening', async () => {
    const preview = await previewImport(parseBackup(JSON.stringify({ app: 'bitchord', version: 1, listening: [month('2025-01', 'bc', 5)] })));
    expect(preview).toMatchObject({ replaces: 'listening', bitchord: true, willWrite: { songs: 0, months: 1 } });
  });
});
