import 'fake-indexeddb/auto';
import { IDBFactory } from 'fake-indexeddb';
import { afterEach, beforeEach, describe, expect, it } from 'vitest';
import { newTrack, tracks } from '../../db/dao';
import { libraryRoots, localUriOf } from '../../db/libraryDao';
import { closeAudenDb, openAudenDb } from '../../db/schema';
import { applyBatch, dropFiles, knownFiles } from './apply';
import type { ScanEntry } from './protocol';

beforeEach(async () => {
  globalThis.indexedDB = new IDBFactory();
  for (const id of ['r', 'rr']) await libraryRoots.put({ id, name: id, kind: 'files', addedAt: 1, scannedAt: null });
});
afterEach(async () => {
  await closeAudenDb();
});

const entry = (relPath: string, modifiedAt = 1): ScanEntry => ({
  relPath, size: 100, modifiedAt, artKey: null,
  tags: { title: relPath, artist: 'A', album: null, albumArtist: null, genre: null, year: null, trackNumber: null, discNumber: null, durationSec: 60 },
});

describe('applyBatch', () => {
  it('adds new files, and re-applying a changed file keeps its id (no ConstraintError)', async () => {
    expect(await applyBatch('r', [entry('a.mp3'), entry('b.mp3')], [])).toEqual({ added: 2, changed: 0 });
    const first = await tracks.byLocalUri(localUriOf('r', 'a.mp3'));
    expect(await applyBatch('r', [entry('a.mp3', 2)], [])).toEqual({ added: 0, changed: 1 });
    const again = await tracks.byLocalUri(localUriOf('r', 'a.mp3'));
    expect(again?.id).toBe(first?.id);
    expect(again?.modifiedAt).toBe(2);
    expect((await tracks.all()).length).toBe(2);
  });
  it('two concurrent applies of the same file leave one row', async () => {
    await Promise.all([applyBatch('r', [entry('a.mp3')], []), applyBatch('r', [entry('a.mp3')], [])]);
    expect((await tracks.all()).length).toBe(1);
  });
  it('lists known files of one root only', async () => {
    await applyBatch('r', [entry('a.mp3')], []);
    await applyBatch('rr', [entry('z.mp3')], []);
    expect(await knownFiles('r')).toEqual([['a.mp3', 100, 1]]);
  });
});

describe('a folder removed mid-scan', () => {
  it('gets no rows from a late batch', async () => {
    await applyBatch('r', [entry('a.mp3')], []);
    await libraryRoots.remove('r');
    expect(await applyBatch('r', [entry('b.mp3'), entry('c.mp3')], [])).toEqual({ added: 0, changed: 0 });
    expect(await tracks.all()).toHaveLength(0);
  });
});

describe('dropFiles', () => {
  it('deletes a file’s row with its playlist entries, but only detaches a row that has a YouTube id', async () => {
    await applyBatch('r', [entry('a.mp3'), entry('b.mp3')], []);
    const b = await tracks.byLocalUri(localUriOf('r', 'b.mp3'));
    await tracks.put({ ...b!, id: b!.id!, videoId: 'dQw4w9WgXcQ' });
    expect(await dropFiles('r', ['a.mp3', 'b.mp3'])).toBe(2);
    expect(await tracks.byLocalUri(localUriOf('r', 'a.mp3'))).toBeUndefined();
    const kept = await tracks.byVideoId('dQw4w9WgXcQ');
    expect(kept).toHaveLength(1);
    expect(kept[0]).toMatchObject({ localUri: null, path: null });
  });
});

describe('removing a folder', () => {
  it('drops its rows by uri prefix, keeping the streaming ones, and not touching another folder', async () => {
    await libraryRoots.put({ id: 'r', name: 'Music', kind: 'files', addedAt: 1, scannedAt: null });
    await applyBatch('r', [entry('a.mp3')], []);
    await applyBatch('rr', [entry('a.mp3')], []);
    await tracks.add(newTrack({ title: 'Stream', artist: 'S', videoId: 'v' }));
    await libraryRoots.remove('r');
    const rest = await tracks.all();
    expect(rest.map((r) => r.localUri).sort()).toEqual([null, localUriOf('rr', 'a.mp3')].sort());
    expect(await libraryRoots.get('r')).toBeUndefined();
    const db = await openAudenDb();
    expect(await db.count('library_root')).toBe(1); // only 'rr' is left
  });
});
