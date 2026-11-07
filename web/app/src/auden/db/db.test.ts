import 'fake-indexeddb/auto';
import { IDBFactory } from 'fake-indexeddb';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { lyrics, newTrack, playlists, spotifyMatches, tracks } from './dao';
import { subscribeToStores } from './notify';
import { openDB } from 'idb';
import { closeAudenDb, DB_NAME, DB_VERSION, openAudenDb, upgradeAudenDb } from './schema';

beforeEach(() => {
  globalThis.indexedDB = new IDBFactory();
});
afterEach(async () => {
  await closeAudenDb();
});

describe('schema', () => {
  it('creates every AudenDb store at the current version', async () => {
    const db = await openAudenDb();
    expect([...db.objectStoreNames].sort()).toEqual(
      ['library_root', 'listening', 'local_art', 'lyrics_variant', 'play_event', 'playlist', 'playlist_entry', 'song_lyrics_pref', 'spotify_match', 'track'].sort(),
    );
    expect(db.version).toBe(DB_VERSION);
  });

  it('lets a newer version in: the open connection is released instead of blocking it', async () => {
    await openAudenDb();
    const upgraded = await new Promise<IDBDatabase>((resolve, reject) => {
      const req = indexedDB.open(DB_NAME, DB_VERSION + 1);
      req.onsuccess = () => resolve(req.result);
      req.onerror = () => reject(req.error);
      req.onblocked = () => reject(new Error('blocked'));
    });
    expect(upgraded.version).toBe(DB_VERSION + 1);
    upgraded.close();
  });
});

describe('upgrade from v1', () => {
  it('keeps the rows, indexes and unique localUri of a frozen v1 database, and adds the library stores', async () => {
    const frozen = await openDB(DB_NAME, 1, {
      upgrade(db) {
        const track = db.createObjectStore('track', { keyPath: 'id', autoIncrement: true });
        track.createIndex('videoId', 'videoId');
        track.createIndex('spotifyId', 'spotifyId');
        track.createIndex('isrc', 'isrc');
        track.createIndex('localUri', 'localUri', { unique: true });
        track.createIndex('path', 'path');
      },
    });
    await frozen.add('track', { title: 'Old', artist: 'A', videoId: 'v1', localUri: 'auden-file://r/old.mp3' });
    frozen.close();
    const db = await openAudenDb();
    expect(db.version).toBe(DB_VERSION);
    expect(await db.getFromIndex('track', 'videoId', 'v1')).toMatchObject({ title: 'Old' });
    expect(await db.getFromIndex('track', 'localUri', 'auden-file://r/old.mp3')).toMatchObject({ title: 'Old' });
    await expect(db.add('track', { title: 'Dup', localUri: 'auden-file://r/old.mp3' } as never)).rejects.toMatchObject({ name: 'ConstraintError' });
    expect([...db.objectStoreNames]).toEqual(expect.arrayContaining(['library_root', 'local_art']));
  });
});

describe('a failed open', () => {
  it('is not remembered: a later call opens normally', async () => {
    await openAudenDb();
    // Another tab upgrades to v2, so this tab's v1 is refused from now on.
    const v2 = await new Promise<IDBDatabase>((resolve, reject) => {
      const req = indexedDB.open(DB_NAME, DB_VERSION + 1);
      req.onsuccess = () => resolve(req.result);
      req.onerror = () => reject(req.error);
    });
    await expect(openAudenDb()).rejects.toMatchObject({ name: 'VersionError' });
    v2.close();
    await new Promise<void>((resolve, reject) => {
      const req = indexedDB.deleteDatabase(DB_NAME);
      req.onsuccess = () => resolve();
      req.onerror = () => reject(req.error);
    });
    expect((await openAudenDb()).version).toBe(DB_VERSION);
  });
});

describe('tracks', () => {
  it('numbers rows, finds them by video id and by local uri', async () => {
    const a = await tracks.add(newTrack({ title: 'A', artist: 'X', videoId: 'v1' }));
    const b = await tracks.add(newTrack({ title: 'B', artist: 'X', localUri: 'auden-file://r/b.mp3' }));
    expect(b).toBeGreaterThan(a);
    expect((await tracks.byVideoId('v1')).map((t) => t.title)).toEqual(['A']);
    expect((await tracks.byLocalUri('auden-file://r/b.mp3'))?.title).toBe('B');
  });

  it('keeps localUri unique but lets many rows have none', async () => {
    await tracks.add(newTrack({ title: 'A', artist: 'X', localUri: 'auden-file://r/a' }));
    await expect(tracks.add(newTrack({ title: 'A2', artist: 'X', localUri: 'auden-file://r/a' }))).rejects.toThrow();
    await tracks.add(newTrack({ title: 'N1', artist: 'X' }));
    await tracks.add(newTrack({ title: 'N2', artist: 'X' }));
    expect(await tracks.all()).toHaveLength(3);
  });

  it('removing a track takes its playlist entries and events with it', async () => {
    const keep = await tracks.add(newTrack({ title: 'Keep', artist: 'X', videoId: 'k' }));
    const gone = await tracks.add(newTrack({ title: 'Gone', artist: 'X', videoId: 'g' }));
    const pl = await playlists.create('Mix');
    await playlists.addTrack(pl.id, keep);
    await playlists.addTrack(pl.id, gone);
    const db = await openAudenDb();
    await db.add('play_event', { trackId: gone, startedAt: 1, listenedMs: 5, completed: false });
    await tracks.remove(gone);
    expect((await playlists.entries(pl.id)).map((e) => e.trackId)).toEqual([keep]);
    expect(await db.count('play_event')).toBe(0);
  });
});

describe('playlists', () => {
  it('appends in order and lists entries by position', async () => {
    const t = [] as number[];
    for (const n of ['a', 'b', 'c']) t.push(await tracks.add(newTrack({ title: n, artist: 'X', videoId: n })));
    const pl = await playlists.create('P');
    expect(pl.id).toMatch(/^local:playlist:/);
    for (const id of t) await playlists.addTrack(pl.id, id);
    const entries = await playlists.entries(pl.id);
    expect(entries.map((e) => e.position)).toEqual([0, 1, 2]);
    expect(entries.map((e) => e.trackId)).toEqual(t);
  });

  it('keeps one playlist\'s entries out of another\'s', async () => {
    const t = await tracks.add(newTrack({ title: 'a', artist: 'X', videoId: 'a' }));
    const p1 = await playlists.create('One');
    const p2 = await playlists.create('Two');
    await playlists.addTrack(p1.id, t);
    expect(await playlists.entries(p2.id)).toEqual([]);
  });

  it('removing a playlist removes its entries but not the tracks', async () => {
    const t = await tracks.add(newTrack({ title: 'a', artist: 'X', videoId: 'a' }));
    const pl = await playlists.create('P');
    await playlists.addTrack(pl.id, t);
    await playlists.remove(pl.id);
    expect(await playlists.get(pl.id)).toBeUndefined();
    expect(await playlists.entries(pl.id)).toEqual([]);
    expect(await tracks.get(t)).toBeDefined();
  });

  it('update merges fields and stamps updatedAt', async () => {
    const pl = await playlists.create('Old');
    await new Promise((r) => setTimeout(r, 2));
    await playlists.update(pl.id, { title: 'New', pinned: true });
    const after = await playlists.get(pl.id);
    expect(after).toMatchObject({ title: 'New', pinned: true, createdAt: pl.createdAt });
    expect(after!.updatedAt).toBeGreaterThan(pl.updatedAt);
  });
});

describe('lyrics and spotify matches', () => {
  it('keeps variants per song and provider and lists one song\'s', async () => {
    const row = (songKey: string, provider: string) => ({ songKey, provider, format: 'lrc', content: provider, fetchedAt: 1 });
    await lyrics.putVariant(row('v1', 'LRCLIB'));
    await lyrics.putVariant(row('v1', 'KuGou'));
    await lyrics.putVariant(row('v2', 'LRCLIB'));
    expect((await lyrics.variants('v1')).map((v) => v.provider).sort()).toEqual(['KuGou', 'LRCLIB']);
    await lyrics.removeVariant('v1', 'KuGou');
    expect(await lyrics.variant('v1', 'KuGou')).toBeUndefined();
    await lyrics.putPref({ songKey: 'v1', activeProvider: 'LRCLIB', offsetMs: -250 });
    expect((await lyrics.pref('v1'))?.offsetMs).toBe(-250);
  });

  it('round-trips a spotify match', async () => {
    await spotifyMatches.put({ spotifyId: 's1', status: 'MANUAL', songJson: '{"id":"v"}', matchedAt: 5 });
    expect((await spotifyMatches.get('s1'))?.status).toBe('MANUAL');
    await spotifyMatches.remove('s1');
    expect(await spotifyMatches.all()).toEqual([]);
  });
});

describe('change notification', () => {
  it('tells local listeners which stores a write touched', async () => {
    const seen = vi.fn();
    const off = subscribeToStores(seen);
    await playlists.create('P');
    off();
    expect(seen).toHaveBeenCalledWith(['playlist']);
  });
});
