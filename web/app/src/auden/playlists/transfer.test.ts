import 'fake-indexeddb/auto';
import { IDBFactory } from 'fake-indexeddb';
import { afterEach, beforeEach, describe, expect, it } from 'vitest';
import type { Track } from '@/data/music/types';
import { newTrack, tracks } from '../db/dao';
import { closeAudenDb } from '../db/schema';
import { createPlaylist, entriesOf } from './store';
import { describeImport, exportPlaylist, importPlaylistFile, MAX_IMPORT_BYTES, uniqueTitle } from './transfer';

beforeEach(() => {
  globalThis.indexedDB = new IDBFactory();
});
afterEach(async () => {
  await closeAudenDb();
});

const yt = (videoId: string, title: string, artist: string): Track => ({
  videoId, title, artists: [{ name: artist, id: null }], album: null, durationSec: 200, explicit: false, thumbnail: null, index: null, plays: null,
});

async function seed() {
  const a = await tracks.add(newTrack({ title: 'One', artist: 'A', localUri: 'auden-file://r/Rock/one.mp3', path: 'Rock/one.mp3', durationMs: 215_000 }));
  const b = await tracks.add(newTrack({ title: 'Two', artist: 'B', localUri: 'auden-file://r/two.flac', path: 'two.flac', durationMs: 100_000 }));
  const { id } = await createPlaylist('My Mix', [a, yt('dQw4w9WgXcQ', 'Streamed', 'S'), b]);
  return id;
}

const asFile = (f: { fileName: string; text: string; mime: string }) => new File([f.text], f.fileName, { type: f.mime });
const titles = async (id: string) => (await entriesOf(id)).map((i) => i.row?.title);

describe('export then import', () => {
  it('round-trips through Auden JSON, keeping the streamed song by video id', async () => {
    const id = await seed();
    const file = (await exportPlaylist(id, 'AUDEN'))!;
    expect(file.fileName).toBe('My Mix.auden.json');
    const summary = await importPlaylistFile(asFile(file));
    expect(summary).toMatchObject({ name: 'My Mix (2)', total: 3, unresolved: [] });
    expect(await titles(summary.playlistId)).toEqual(['One', 'Streamed', 'Two']);
  });
  it('round-trips through XSPF', async () => {
    const id = await seed();
    const summary = await importPlaylistFile(asFile((await exportPlaylist(id, 'XSPF'))!));
    expect(await titles(summary.playlistId)).toEqual(['One', 'Streamed', 'Two']);
  });
  it('M3U8 carries only songs that have a file, and they match back by path', async () => {
    const id = await seed();
    const file = (await exportPlaylist(id, 'M3U8'))!;
    expect(file.text).toContain('Rock/one.mp3');
    expect(file.text).not.toContain('Streamed');
    const summary = await importPlaylistFile(asFile(file));
    expect(await titles(summary.playlistId)).toEqual(['One', 'Two']);
    expect(describeImport(summary)).toBe('“My Mix (2)”: 2 songs imported.');
  });
});

describe('online search limits', () => {
  it('runs at most the cap, a few at once, and reports the rest as not found, in file order', async () => {
    const lines = Array.from({ length: 600 }, (_, i) => `#EXTINF:100,Artist - Song ${i}\n/nowhere/${i}.mp3`).join('\n');
    let calls = 0;
    let live = 0;
    let peak = 0;
    const search = async (e: { title?: string | null }) => {
      calls++;
      peak = Math.max(peak, ++live);
      await new Promise((r) => setTimeout(r, 0));
      live--;
      return e.title === 'Song 0' ? yt('abcdefghijk', 'Hit', 'A') : null;
    };
    const progress: number[] = [];
    const s = await importPlaylistFile(new File([`#EXTM3U\n${lines}\n`], 'big.m3u'), { search, maxSearches: 500, onProgress: (d) => progress.push(d) });
    expect(calls).toBe(500);
    expect(peak).toBeLessThanOrEqual(4);
    expect(s).toMatchObject({ online: 1, total: 600 });
    expect(s.unresolved).toHaveLength(599);
    expect(progress.at(-1)).toBe(500);
  });
  it('can be cancelled', async () => {
    const ctl = new AbortController();
    const search = async () => {
      ctl.abort();
      return null;
    };
    await expect(importPlaylistFile(new File(['#EXTM3U\n/a.mp3\n/b.mp3\n'], 'c.m3u'), { search, signal: ctl.signal })).rejects.toThrow(/cancelled/);
  });
});

describe('import', () => {
  it('matches a path from another device by file name, and by tags, and lists what it cannot find', async () => {
    await seed();
    const m3u = '#EXTM3U\n#EXTINF:215,A - One\n/storage/sd/Music/Rock/one.mp3\n#EXTINF:100,B - Two\n/gone/elsewhere.mp3\n/x/unknown.mp3\n';
    const s = await importPlaylistFile(new File([m3u], 'phone.m3u8'));
    expect(s.local).toBe(2);
    expect(s.unresolved).toEqual([{ title: 'unknown', artist: '' }]);
    expect(describeImport(s)).toContain('2 of 3 songs found, 1 not found');
  });
  it('searches online for what is left when given a search, and never trusts a hostile video id', async () => {
    const json = JSON.stringify({ tracks: [{ title: 'Ghost', artist: 'G', videoId: 'spotify:123' }, { title: 'Real', videoId: 'dQw4w9WgXcQ' }] });
    const s = await importPlaylistFile(new File([json], 'x.auden.json'), { search: async (e) => (e.title === 'Ghost' ? yt('abcdefghijk', 'Ghost', 'G') : null) });
    expect(s).toMatchObject({ local: 1, online: 1, unresolved: [] });
    expect(await titles(s.playlistId)).toEqual(['Ghost', 'Real']);
  });
  it('refuses a file that is too big, unreadable, empty, or with nothing findable', async () => {
    await expect(importPlaylistFile(new File([new Uint8Array(MAX_IMPORT_BYTES + 1)], 'big.m3u'))).rejects.toThrow(/too big/);
    await expect(importPlaylistFile(new File(['{not json'], 'a.auden.json'))).rejects.toThrow(/can read/);
    await expect(importPlaylistFile(new File(['#EXTM3U\n'], 'e.m3u'))).rejects.toThrow(/empty/);
    await expect(importPlaylistFile(new File(['#EXTM3U\n/nowhere/a.mp3\n'], 'n.m3u'))).rejects.toThrow(/could be found/);
  });
  it('names collide politely', () => {
    expect(uniqueTitle('Mix', new Set(['Mix', 'Mix (2)']))).toBe('Mix (3)');
    expect(uniqueTitle('Mix', new Set())).toBe('Mix');
  });
});
