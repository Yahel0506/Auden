import 'fake-indexeddb/auto';
import { IDBFactory } from 'fake-indexeddb';
import { afterEach, beforeEach, describe, expect, it } from 'vitest';
import type { Track } from '@/data/music/types';
import { closeAudenDb } from '../db/schema';
import { saveSpotifyPlaylist } from './cloudSaver';
import { replaceSpotifyMatch } from './manualMatch';
import { addTracks, bySource, entriesOf } from './store';

beforeEach(() => {
  globalThis.indexedDB = new IDBFactory();
});
afterEach(async () => {
  await closeAudenDb();
});

const song = (c: string): Track => ({ videoId: c.repeat(11), title: c, artists: [{ name: 'A', id: null }], album: null, durationSec: 200, explicit: false, thumbnail: null, index: null, plays: null });
const deps = (m: Record<string, Track>) => ({
  youtube: async () => ({ title: '', thumbnail: null, tracks: [] as Track[] }),
  spotify: { tracks: async () => ({ items: Object.keys(m).map((id) => ({ id })), complete: true }), match: async (i: { id: string }) => m[i.id] ?? null },
});

describe('replaceSpotifyMatch', () => {
  it('swaps the wrong song for the right one in place, leaving hand-added songs alone', async () => {
    await saveSpotifyPlaylist(deps({ s1: song('a'), s2: song('b') }), 'P');
    const copy = (await bySource('spotify:playlist:P'))!;
    await addTracks(copy.id, [song('h')]);
    expect(await replaceSpotifyMatch('s1', song('z'))).toBe(1);
    expect((await entriesOf(copy.id)).map((i) => i.row?.title)).toEqual(['z', 'b', 'h']);
  });
  it('removes the wrong entry when the copy already holds the right song', async () => {
    await saveSpotifyPlaylist(deps({ s1: song('a'), s2: song('b') }), 'P');
    const copy = (await bySource('spotify:playlist:P'))!;
    await replaceSpotifyMatch('s1', song('b'));
    expect((await entriesOf(copy.id)).map((i) => i.row?.title)).toEqual(['b']);
  });
  it('ignores a replacement that cannot be stored, and an id no copy holds', async () => {
    await saveSpotifyPlaylist(deps({ s1: song('a') }), 'P');
    expect(await replaceSpotifyMatch('s1', { ...song('q'), videoId: 'spotify:1' })).toBe(0);
    expect(await replaceSpotifyMatch('nope', song('y'))).toBe(0);
  });
});
