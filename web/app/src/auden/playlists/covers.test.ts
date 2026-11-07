import 'fake-indexeddb/auto';
import { IDBFactory } from 'fake-indexeddb';
import { afterEach, beforeEach, describe, expect, it } from 'vitest';
import { closeAudenDb, openAudenDb } from '../db/schema';
import { mosaicHash, mosaicKey, mosaicSources, refreshMosaic } from './covers';

beforeEach(() => {
  globalThis.indexedDB = new IDBFactory();
});
afterEach(async () => {
  await closeAudenDb();
});

const addPlaylist = async (coverUrl: string | null = null) => {
  const db = await openAudenDb();
  await db.put('playlist', { id: 'local:playlist:p', title: 'P', createdAt: 1, updatedAt: 1, pinned: false, sourceId: null, coverUrl });
};
const fakeDraw = async () => ({ bytes: new ArrayBuffer(8), mime: 'image/jpeg' });

describe('mosaicSources', () => {
  it('takes the first four distinct thumbnails and needs two', () => {
    expect(mosaicSources(['a', 'a', null, 'b', 'c', 'd', 'e'])).toEqual(['a', 'b', 'c', 'd']);
    expect(mosaicSources(['a', 'a'])).toEqual([]);
    expect(mosaicSources([null, undefined])).toEqual([]);
  });
  it('never uses session-only blob: urls', () => {
    expect(mosaicSources(['blob:x', 'a'])).toEqual([]);
  });
  it('keys change exactly when the covers do', () => {
    expect(mosaicHash(['a', 'b'])).toBe(mosaicHash(['a', 'b']));
    expect(mosaicHash(['a', 'b'])).not.toBe(mosaicHash(['a', 'c']));
    expect(mosaicKey('p', ['a', 'b'])).toMatch(/^playlist:p:[0-9a-f]{8}$/);
  });
});

describe('refreshMosaic races', () => {
  it('two refreshes at once leave one playlist cover, and a playlist deleted mid-draw leaves none', async () => {
    await addPlaylist();
    const db = await openAudenDb();
    await Promise.all([refreshMosaic('local:playlist:p', ['a', 'b'], fakeDraw), refreshMosaic('local:playlist:p', ['a', 'c'], fakeDraw)]);
    expect((await db.getAllKeys('local_art')).filter((k) => String(k).startsWith('playlist:'))).toHaveLength(1);
    const slow = async () => {
      await db.delete('playlist', 'local:playlist:p'); // gone while the image is being drawn
      return { bytes: new ArrayBuffer(4), mime: 'image/jpeg' };
    };
    await refreshMosaic('local:playlist:p', ['x', 'y'], slow);
    expect((await db.getAllKeys('local_art')).filter((k) => String(k).includes('playlist:local:playlist:p:'))).toHaveLength(1);
  });
});

describe('refreshMosaic', () => {
  it('stores a cover, replaces it when the thumbnails change (old one deleted), clears it below two', async () => {
    await addPlaylist();
    const url1 = await refreshMosaic('local:playlist:p', ['a', 'b'], fakeDraw);
    const db = await openAudenDb();
    expect(url1).toMatch(/^auden-art:\/\//);
    expect(await db.count('local_art')).toBe(1);
    const url2 = await refreshMosaic('local:playlist:p', ['a', 'c'], fakeDraw);
    expect(url2).not.toBe(url1);
    expect(await db.count('local_art')).toBe(1);
    expect((await db.get('playlist', 'local:playlist:p'))?.coverUrl).toBe(url2);
    expect(await refreshMosaic('local:playlist:p', ['a'], fakeDraw)).toBeNull();
    expect(await db.count('local_art')).toBe(0);
  });
  it('keeps a cloud copy’s own cover and keeps the old mosaic when drawing fails', async () => {
    await addPlaylist('https://i.scdn.co/x.jpg');
    expect(await refreshMosaic('local:playlist:p', ['a', 'b'], fakeDraw)).toBe('https://i.scdn.co/x.jpg');
    const db = await openAudenDb();
    expect(await db.count('local_art')).toBe(0);
  });
  it('leaves the cover alone when no image could be read', async () => {
    await addPlaylist();
    expect(await refreshMosaic('local:playlist:p', ['a', 'b'], async () => null)).toBeNull();
  });
});
