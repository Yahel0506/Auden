import 'fake-indexeddb/auto';
import { IDBFactory } from 'fake-indexeddb';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import type { Track } from '@/data/music/types';
import { localTrackId } from '@/playback/source';
import { newTrack, tracks } from '../db/dao';
import { localUriOf } from '../db/libraryDao';
import { closeAudenDb, openAudenDb } from '../db/schema';
import { requestPersistence } from '../db/persist';
import { dropFiles } from '../library/scan/apply';
import {
  addTracks,
  bySource,
  createPlaylist,
  deletePlaylist,
  duplicatePlaylist,
  EmptySourceError,
  ensureTrackRow,
  entriesOf,
  listPlaylists,
  membership,
  moveEntry,
  playlistArtUrl,
  playlistTracks,
  RefusedTrackError,
  removeEntry,
  renamePlaylist,
  saveFromSource,
  setPlaylistCover,
} from './store';

vi.mock('../db/persist', () => ({ requestPersistence: vi.fn(async () => true) }));

beforeEach(() => {
  globalThis.indexedDB = new IDBFactory();
  vi.mocked(requestPersistence).mockClear();
});
afterEach(async () => {
  await closeAudenDb();
});

const yt = (videoId: string, title = videoId, artist = 'Artist', durationSec: number | null = 200, thumbnail: string | null = `https://i.ytimg.com/${videoId}.jpg`): Track => ({
  videoId,
  title,
  artists: artist.split(', ').map((name, i) => ({ name, id: i === 0 ? `UC${name}` : null })),
  album: { name: 'Album', id: 'MPRE1' },
  durationSec,
  explicit: false,
  thumbnail,
  index: null,
  plays: null,
});

const ids = async (playlistId: string) => (await entriesOf(playlistId)).map(({ row }) => row?.videoId ?? row?.title);
const entryIds = async (playlistId: string) => (await entriesOf(playlistId)).map(({ entry }) => entry.entryId);

describe('ensureTrackRow', () => {
  it('maps a local id to its row and a known video to its row (the one with a file first)', async () => {
    const plain = await tracks.add(newTrack({ title: 'S', artist: 'A', videoId: 'v1' }));
    const file = await tracks.add(newTrack({ title: 'S', artist: 'A', videoId: 'v1', localUri: localUriOf('r', 's.mp3') }));
    expect(plain).not.toBe(file);
    expect(await ensureTrackRow(yt('v1'))).toBe(file);
    expect(await ensureTrackRow({ ...yt('x'), videoId: localTrackId(plain), source: 'local' })).toBe(plain);
  });

  it('inserts a YouTube song once, with its artists joined and no blob: thumbnail', async () => {
    const id = await ensureTrackRow(yt('v2', 'Song', 'A, B', 180, 'blob:http://localhost/123'));
    expect(await ensureTrackRow(yt('v2'))).toBe(id);
    const row = await tracks.get(id);
    expect(row).toMatchObject({ videoId: 'v2', title: 'Song', artist: 'A, B', artistId: 'UCA', album: 'Album', albumId: 'MPRE1', durationMs: 180000, thumbnailUrl: null, localUri: null });
    expect((await tracks.all()).length).toBe(1);
  });

  it('refuses Spotify rows, clips, unavailable rows and a vanished local row', async () => {
    await expect(ensureTrackRow(yt('spotify:abc'))).rejects.toBeInstanceOf(RefusedTrackError);
    await expect(ensureTrackRow({ ...yt('auden:clip:1'), source: 'clip' })).rejects.toBeInstanceOf(RefusedTrackError);
    await expect(ensureTrackRow({ ...yt('v3'), unavailable: 'missing' })).rejects.toBeInstanceOf(RefusedTrackError);
    await expect(ensureTrackRow({ ...yt('x'), videoId: localTrackId(999), source: 'local' })).rejects.toBeInstanceOf(RefusedTrackError);
    expect(await tracks.all()).toEqual([]);
  });
});

describe('playlists', () => {
  it('creates with deduplicated songs, skips what it cannot store, and asks for persistence only the first time', async () => {
    const a = await createPlaylist('Mix', [yt('a'), yt('b'), yt('a'), yt('spotify:x')]);
    expect(a).toMatchObject({ added: 2, duplicates: 1, skipped: 1 });
    expect(a.id).toMatch(/^local:playlist:[0-9a-f-]{36}$/);
    expect(await ids(a.id)).toEqual(['a', 'b']);
    await createPlaylist('Other');
    expect(requestPersistence).toHaveBeenCalledTimes(1);
  });

  it('adds only songs it does not hold: same row, or the same song (sameSong)', async () => {
    const { id } = await createPlaylist('P', [yt('a', 'Hello', 'Adele', 295)]);
    const r = await addTracks(id, [yt('a'), yt('other-upload', 'Hello (Official Video)', 'Adele', 296), yt('c', 'Else')]);
    expect(r).toEqual({ added: 1, duplicates: 2, skipped: 0 });
    expect(await ids(id)).toEqual(['a', 'c']);
  });

  it('membership finds the playlists holding the song by id or by metadata', async () => {
    const p1 = await createPlaylist('One', [yt('a', 'Hello', 'Adele', 295)]);
    const p2 = await createPlaylist('Two', [yt('b')]);
    const local = await tracks.add(newTrack({ title: 'Hello', artist: 'Adele', durationMs: 296000, localUri: localUriOf('r', 'hello.mp3') }));
    const p3 = await createPlaylist('Three', [local]);
    const got = await membership(yt('zzz', 'Hello', 'Adele', 294));
    expect([...got].sort()).toEqual([p1.id, p3.id].sort());
    expect(got.has(p2.id)).toBe(false);
    expect(await membership(yt('spotify:1'))).toEqual(new Set());
  });

  it('moves by entry id, keeps entry ids stable, and ignores stale ids', async () => {
    const { id } = await createPlaylist('P', ['a', 'b', 'c', 'd'].map((v) => yt(v)));
    const [a, b, c, d] = await entryIds(id);
    expect(await moveEntry(id, d!, a!)).toBe(true);
    expect(await ids(id)).toEqual(['d', 'a', 'b', 'c']);
    expect(await removeEntry(a!)).toBe(true);
    expect(await entryIds(id)).toEqual([d, b, c]);
    // A UI still showing `a` drags it, or drops before it: nothing happens.
    expect(await moveEntry(id, a!, null)).toBe(false);
    expect(await moveEntry(id, b!, a!)).toBe(false);
    expect(await removeEntry(a!)).toBe(false);
    expect(await moveEntry(id, d!, null)).toBe(true);
    expect(await ids(id)).toEqual(['b', 'c', 'd']);
    expect((await entriesOf(id)).map(({ entry }) => entry.position)).toEqual([0, 1, 2]);
    expect(await entryIds(id)).toEqual([b, c, d]);
    // An entry of another playlist is not moved here.
    const other = await createPlaylist('Q', [yt('z')]);
    const [z] = await entryIds(other.id);
    expect(await moveEntry(id, z!, b!)).toBe(false);
  });

  it('renames, and duplicates under a unique name with its own copy of the cover', async () => {
    const { id } = await createPlaylist('Road', [yt('a'), yt('b')]);
    const key = `playlist:${id}:h1`;
    await setPlaylistCover(id, playlistArtUrl(id, 'h1'), { key, bytes: new Uint8Array([1, 2]).buffer, mime: 'image/jpeg' });
    expect(await renamePlaylist(id, '  ')).toBe(false);
    expect(await renamePlaylist(id, 'Trip')).toBe(true);
    const copy = (await duplicatePlaylist(id))!;
    const copy2 = (await duplicatePlaylist(id))!;
    const titles = (await listPlaylists()).map((s) => s.playlist.title);
    expect(titles).toEqual(expect.arrayContaining(['Trip', 'Trip copy', 'Trip copy 2']));
    expect(await ids(copy)).toEqual(['a', 'b']);
    const db = await openAudenDb();
    const copyRow = await db.get('playlist', copy);
    expect(copyRow?.coverUrl).toBe(playlistArtUrl(copy, 'h1'));
    expect(copyRow?.sourceId).toBeNull();
    await deletePlaylist(copy);
    expect(await db.get('local_art', key)).toBeDefined();
    expect(copy2).not.toBe(copy);
  });

  it('delete takes the entries and its own cover, not the tracks or other covers', async () => {
    const { id } = await createPlaylist('P', [yt('a'), yt('b')]);
    const other = await createPlaylist('Q', [yt('a')]);
    const db = await openAudenDb();
    await setPlaylistCover(id, playlistArtUrl(id, 'x'), { key: `playlist:${id}:x`, bytes: new ArrayBuffer(4), mime: 'image/jpeg' });
    await db.put('local_art', { key: 'album:a|b', bytes: new ArrayBuffer(1), mime: 'image/jpeg' });
    expect(await deletePlaylist(id)).toBe(true);
    expect(await entriesOf(id)).toEqual([]);
    expect(await db.get('playlist', id)).toBeUndefined();
    expect(await db.get('local_art', `playlist:${id}:x`)).toBeUndefined();
    expect(await db.get('local_art', 'album:a|b')).toBeDefined();
    expect(await ids(other.id)).toEqual(['a']);
    expect((await tracks.all()).length).toBe(2);
  });

  it('a new cover replaces the old one of its own in the same write; blob: covers are not kept', async () => {
    const { id } = await createPlaylist('P', [], { coverUrl: 'blob:http://x/1' });
    const db = await openAudenDb();
    expect((await db.get('playlist', id))?.coverUrl).toBeNull();
    await setPlaylistCover(id, playlistArtUrl(id, 'one'), { key: `playlist:${id}:one`, bytes: new ArrayBuffer(1), mime: 'image/jpeg' });
    await setPlaylistCover(id, playlistArtUrl(id, 'two'), { key: `playlist:${id}:two`, bytes: new ArrayBuffer(1), mime: 'image/jpeg' });
    expect((await db.getAllKeys('local_art')).sort()).toEqual([`playlist:${id}:two`]);
    await setPlaylistCover(id, 'https://cdn/cover.jpg');
    expect(await db.getAllKeys('local_art')).toEqual([]);
  });

  it('plays a file-only row as a local track and a row with a YouTube id as a YouTube song', async () => {
    const local = await tracks.add(newTrack({ title: 'Mine', artist: 'Me', durationMs: 1000, localUri: localUriOf('r', 'm.mp3') }));
    const linked = await tracks.add(newTrack({ title: 'Both', artist: 'X & Y', videoId: 'vb', localUri: localUriOf('r', 'b.mp3') }));
    const { id } = await createPlaylist('P', [local, linked, yt('vc')]);
    const list = await playlistTracks(id);
    expect(list.map((t) => [t.videoId, t.source])).toEqual([
      [localTrackId(local), 'local'],
      ['vb', undefined],
      ['vc', undefined],
    ]);
    expect(list[1]!.artists.map((a) => a.name)).toEqual(['X', 'Y']);
  });

  it('a file that leaves the folder keeps its entries when its row has a YouTube id (dropFiles)', async () => {
    const onlyFile = await tracks.add(newTrack({ title: 'F', artist: 'A', localUri: localUriOf('r', 'f.mp3') }));
    const linked = await tracks.add(newTrack({ title: 'L', artist: 'A', videoId: 'vl', localUri: localUriOf('r', 'l.mp3') }));
    const { id } = await createPlaylist('P', [onlyFile, linked]);
    expect(await dropFiles('r', ['f.mp3', 'l.mp3'])).toBe(2);
    const left = await entriesOf(id);
    expect(left.map(({ row }) => row?.videoId)).toEqual(['vl']);
    expect(left[0]!.row?.localUri).toBeNull();
    expect((await playlistTracks(id)).map((t) => t.videoId)).toEqual(['vl']);
    // Positions may have a gap now; appending and moving still work.
    await addTracks(id, [yt('n')]);
    const [l, n] = await entryIds(id);
    expect(await moveEntry(id, n!, l!)).toBe(true);
    expect(await ids(id)).toEqual(['n', 'vl']);
  });
});

describe('saveFromSource', () => {
  const SRC = 'spotify:playlist:abc';

  it('refuses an empty or all-unstorable save, so a failed read never empties a copy', async () => {
    await expect(saveFromSource({ sourceId: SRC, title: 'S', tracks: [] })).rejects.toBeInstanceOf(EmptySourceError);
    await expect(saveFromSource({ sourceId: SRC, title: 'S', tracks: [yt('spotify:1')] })).rejects.toBeInstanceOf(EmptySourceError);
    expect(await bySource(SRC)).toBeUndefined();
  });

  it('creates once, then syncs: removes what left a complete original, keeps hand-added and unmatched-but-listed songs', async () => {
    const first = await saveFromSource({
      sourceId: SRC, title: 'Spotify mix', coverUrl: 'https://cdn/a.jpg',
      tracks: [yt('a'), yt('b'), yt('c')], spotifyIds: ['S1', 'S2', 'S3'], listedSpotifyIds: ['S1', 'S2', 'S3'], complete: true,
    });
    expect(first).toMatchObject({ created: true, added: 3, removed: 0, name: 'Spotify mix' });
    const id = first.playlistId;
    await addTracks(id, [yt('mine')]);
    await renamePlaylist(id, 'My name');

    // A short read lists only S1: nothing is removed.
    const short = await saveFromSource({ sourceId: SRC, title: 'x', tracks: [yt('a')], spotifyIds: ['S1'], listedSpotifyIds: ['S1'], complete: false });
    expect(short).toMatchObject({ created: false, added: 0, removed: 0, name: 'My name' });
    expect(await ids(id)).toEqual(['a', 'b', 'c', 'mine']);

    // Complete: S2 left, S3 is listed but did not match this time, S4 is new.
    const sync = await saveFromSource({ sourceId: SRC, title: 'x', tracks: [yt('a'), yt('d')], spotifyIds: ['S1', 'S4'], listedSpotifyIds: ['S1', 'S3', 'S4'], complete: true });
    expect(sync).toMatchObject({ created: false, added: 1, removed: 1 });
    expect(await ids(id)).toEqual(['a', 'c', 'mine', 'd']);
    expect((await entriesOf(id)).map(({ entry }) => entry.position)).toEqual([0, 1, 2, 3]);
  });

  it('a hand-added song that Spotify also lists stays when Spotify drops it', async () => {
    const { playlistId: id } = await saveFromSource({ sourceId: SRC, title: 'S', tracks: [yt('a')], spotifyIds: ['S1'], listedSpotifyIds: ['S1'], complete: true });
    await addTracks(id, [yt('v', 'Hand', 'H')]);
    // The original gains S2, which matches the hand-added song (another upload of it).
    expect(await saveFromSource({ sourceId: SRC, title: 'S', tracks: [yt('a'), yt('v2', 'Hand', 'H')], spotifyIds: ['S1', 'S2'], listedSpotifyIds: ['S1', 'S2'], complete: true }))
      .toMatchObject({ added: 0, removed: 0 });
    // Then drops it.
    expect(await saveFromSource({ sourceId: SRC, title: 'S', tracks: [yt('a')], spotifyIds: ['S1'], listedSpotifyIds: ['S1'], complete: true }))
      .toMatchObject({ added: 0, removed: 0 });
    expect(await ids(id)).toEqual(['a', 'v']);
  });

  it('two Spotify ids matching one video make one entry, which stays while either is listed', async () => {
    const { playlistId: id } = await saveFromSource({ sourceId: SRC, title: 'S', tracks: [yt('v'), yt('v')], spotifyIds: ['S1', 'S2'], listedSpotifyIds: ['S1', 'S2'], complete: true });
    expect(await ids(id)).toEqual(['v']);
    expect(await saveFromSource({ sourceId: SRC, title: 'S', tracks: [yt('v')], spotifyIds: ['S2'], listedSpotifyIds: ['S2'], complete: true }))
      .toMatchObject({ added: 0, removed: 0 });
    expect(await ids(id)).toEqual(['v']);
    expect((await entriesOf(id))[0]!.entry.fromSpotifyId).toBe('S2');
  });

  it('a YouTube copy only gains songs, and the newest cover replaces its own mosaic', async () => {
    const src = 'PLyoutube';
    const { playlistId: id } = await saveFromSource({ sourceId: src, title: 'YT', tracks: [yt('a'), yt('b')] });
    const db = await openAudenDb();
    await setPlaylistCover(id, playlistArtUrl(id, 'm'), { key: `playlist:${id}:m`, bytes: new ArrayBuffer(1), mime: 'image/jpeg' });
    expect(await saveFromSource({ sourceId: src, title: 'YT', tracks: [yt('c')], coverUrl: 'https://cdn/new.jpg', complete: true }))
      .toMatchObject({ added: 1, removed: 0 });
    expect(await ids(id)).toEqual(['a', 'b', 'c']);
    expect((await db.get('playlist', id))?.coverUrl).toBe('https://cdn/new.jpg');
    expect(await db.getAllKeys('local_art')).toEqual([]);
  });

  it('two concurrent saves of one source make one copy', async () => {
    const save = () => saveFromSource({ sourceId: SRC, title: 'S', tracks: [yt('a'), yt('b')], spotifyIds: ['S1', 'S2'], listedSpotifyIds: ['S1', 'S2'], complete: true });
    const [r1, r2] = await Promise.all([save(), save()]);
    expect(r1.playlistId).toBe(r2.playlistId);
    expect([r1.created, r2.created].sort()).toEqual([false, true]);
    expect((await listPlaylists()).length).toBe(1);
    expect(await ids(r1.playlistId)).toEqual(['a', 'b']);
    expect((await tracks.all()).length).toBe(2);
  });
});
