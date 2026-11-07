import 'fake-indexeddb/auto';
import { IDBFactory } from 'fake-indexeddb';
import { afterEach, beforeEach, describe, expect, it } from 'vitest';
import { tracks, newTrack } from '../db/dao';
import { closeAudenDb } from '../db/schema';
import { lyricsKeyFor } from './key';

beforeEach(() => {
  globalThis.indexedDB = new IDBFactory();
});
afterEach(async () => {
  await closeAudenDb();
});

describe('lyricsKeyFor', () => {
  it('is the video id for a YouTube song', async () => {
    expect(await lyricsKeyFor('abcdefghijk')).toBe('abcdefghijk');
  });

  it('is the file\'s uri for a file linked to nothing, so the key survives a reindex', async () => {
    const id = await tracks.add(newTrack({ title: 'T', artist: 'A', localUri: 'auden-file://r/a.mp3' }));
    expect(await lyricsKeyFor(`auden:local:${id}`)).toBe('auden-file://r/a.mp3');
  });

  it('is the video id for a file linked to a YouTube song, which then shares its lyrics (as on Android)', async () => {
    const id = await tracks.add(newTrack({ title: 'T', artist: 'A', localUri: 'auden-file://r/b.mp3', videoId: 'linkedVideo1' }));
    expect(await lyricsKeyFor(`auden:local:${id}`)).toBe('linkedVideo1');
  });

  it('falls back to the id itself when the row is gone', async () => {
    expect(await lyricsKeyFor('auden:local:9999')).toBe('auden:local:9999');
  });
});
