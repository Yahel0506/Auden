import 'fake-indexeddb/auto';
import { IDBFactory } from 'fake-indexeddb';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import type { Track } from '@/data/music/types';
import { newTrack, tracks } from '../../db/dao';
import { closeAudenDb } from '../../db/schema';
import { acceptSuggestion, identifyRow, needsIdentifying, startIdentify, useIdentify } from './identifier';

beforeEach(() => {
  globalThis.indexedDB = new IDBFactory();
});
afterEach(async () => {
  await closeAudenDb();
});

const song = (videoId: string, title: string, artist: string, durationSec: number, album: string | null = null): Track => ({
  videoId, title, artists: [{ name: artist, id: 'UC1' }], album: album ? { name: album, id: 'MPRE1' } : null,
  durationSec, explicit: false, thumbnail: null, index: null, plays: null,
});
const file = (over: Parameters<typeof newTrack>[0]) => newTrack({ localUri: 'auden-file://r/x.mp3', path: 'x.mp3', durationMs: 200_000, ...over });

describe('identifyRow cascade', () => {
  it('takes a video id from the file name without searching', async () => {
    const search = vi.fn(async () => []);
    const patch = await identifyRow(file({ title: 'A', artist: 'B', path: 'Dir/A [dQw4w9WgXcQ].m4a' }), search);
    expect(patch).toMatchObject({ videoId: 'dQw4w9WgXcQ', identStatus: 'MATCHED', identSource: 'FILE_NAME' });
    expect(search).not.toHaveBeenCalled();
  });
  it('does not take a title word that merely looks like an id', async () => {
    const patch = await identifyRow(file({ title: 'T', artist: 'Unknown Artist', path: 'Artist - T [Butterflies].mp3' }), async () => []);
    expect(patch.identSource).not.toBe('FILE_NAME');
  });
  it('links a certain search match (artist known, title/artist/length agree) and stops after the first query', async () => {
    const search = vi.fn(async () => [song('vid11111111', 'Hello', 'Adele', 200), song('vid22222222', 'Hello (Karaoke)', 'Sing King', 200)]);
    const patch = await identifyRow(file({ title: 'Hello', artist: 'Adele' }), search);
    expect(patch).toMatchObject({ videoId: 'vid11111111', identStatus: 'MATCHED', identSource: 'SEARCH' });
    expect(search).toHaveBeenCalledTimes(1);
  });
  it('sends a doubtful match (unknown artist) to review with a suggestion', async () => {
    const patch = await identifyRow(file({ title: 'Hello', artist: 'Unknown Artist' }), async () => [song('vid11111111', 'Hello', 'Adele', 200)]);
    expect(patch).toMatchObject({ identStatus: 'REVIEW', suggestedVideoId: 'vid11111111', suggestedTitle: 'Hello' });
    expect(patch.videoId).toBeUndefined();
  });
  it('marks NOT_FOUND when the search is empty', async () => {
    expect(await identifyRow(file({ title: 'Zzz', artist: 'Nobody' }), async () => [])).toMatchObject({ identStatus: 'NOT_FOUND' });
  });
  it('a wrong-length same-title result is not certain', async () => {
    const patch = await identifyRow(file({ title: 'Hello', artist: 'Adele', durationMs: 200_000 }), async () => [song('vid11111111', 'Hello', 'Adele', 400)]);
    expect(patch.identStatus).toBe('REVIEW');
  });
});

describe('startIdentify', () => {
  it('judges every pending file once, saves as it goes, and review rows can be accepted', async () => {
    const a = await tracks.add(file({ title: 'Hello', artist: 'Adele', localUri: 'auden-file://r/a.mp3', path: 'a.mp3' }));
    await tracks.add(file({ title: 'Done', artist: 'X', localUri: 'auden-file://r/b.mp3', path: 'b.mp3', identStatus: 'NOT_FOUND' }));
    const c = await tracks.add(file({ title: 'Maybe', artist: 'Unknown Artist', localUri: 'auden-file://r/c.mp3', path: 'c.mp3' }));
    const search = vi.fn(async (q: string) => (q.startsWith('Hello') ? [song('vid11111111', 'Hello', 'Adele', 200)] : [song('vid33333333', 'Maybe', 'Band', 200)]));
    await startIdentify(search);
    expect(useIdentify.getState()).toMatchObject({ running: false, done: 2, total: 2 });
    expect((await tracks.get(a))?.videoId).toBe('vid11111111');
    expect((await tracks.get(c))?.identStatus).toBe('REVIEW');
    expect(needsIdentifying((await tracks.get(c))!)).toBe(false);
    await acceptSuggestion(c);
    expect(await tracks.get(c)).toMatchObject({ videoId: 'vid33333333', identSource: 'MANUAL', suggestedVideoId: null });
  });
});
