import 'fake-indexeddb/auto';
import { IDBFactory } from 'fake-indexeddb';
import { afterEach, beforeEach, describe, expect, it } from 'vitest';
import type { Track } from '@/data/music/types';
import { closeAudenDb } from '../db/schema';
import { type CloudDeps, resaveCopy, saveSpotifyPlaylist, saveYouTubeList, spotifySourceId, youtubeSourceId } from './cloudSaver';
import { addTracks, bySource, entriesOf } from './store';

beforeEach(() => {
  globalThis.indexedDB = new IDBFactory();
});
afterEach(async () => {
  await closeAudenDb();
});

const song = (videoId: string, title = videoId): Track => ({
  videoId, title, artists: [{ name: 'A', id: null }], album: null, durationSec: 200, explicit: false, thumbnail: `https://img/${videoId}`, index: null, plays: null,
});
const ids = (n: number) => Array.from({ length: n }, (_, i) => String.fromCharCode(97 + i).repeat(11));
const titles = async (id: string) => (await entriesOf(id)).map((i) => i.row?.videoId);

function deps(yt: () => Track[], sp: () => { items: Array<{ id: string }>; complete: boolean }, matches: Record<string, Track | null>): CloudDeps {
  return {
    youtube: async () => ({ title: 'Mix', thumbnail: 'https://cover/x', tracks: yt() }),
    spotify: { tracks: async () => sp(), match: async (item) => matches[item.id] ?? null },
  };
}

describe('YouTube copies', () => {
  it('saves once under the source id (no VL), then only adds on a re-save, even for songs the listener removed', async () => {
    const [a, b, c] = ids(3) as [string, string, string];
    let tracks = [song(a), song(b)];
    const d = deps(() => tracks, () => ({ items: [], complete: true }), {});
    const first = await saveYouTubeList(d, 'VLPL123');
    expect(first).toMatchObject({ created: true, added: 2, unmatched: 0 });
    const copy = (await bySource('PL123'))!;
    expect(copy.coverUrl).toBe('https://cover/x');
    expect(youtubeSourceId('VLPL123')).toBe('PL123');
    tracks = [song(b), song(c)];
    const second = await saveYouTubeList(d, 'VLPL123');
    expect(second).toMatchObject({ created: false, added: 1, removed: 0 });
    expect(await titles(copy.id)).toEqual([a, b, c]);
  });
  it('an empty read changes nothing and says so', async () => {
    const d = deps(() => [], () => ({ items: [], complete: true }), {});
    expect(await saveYouTubeList(d, 'VLPL9')).toEqual({ failed: 'There was nothing to save.' });
    expect(await bySource('PL9')).toBeUndefined();
  });
});

describe('Spotify copies', () => {
  const [a, b, c] = ids(3) as [string, string, string];
  const matches = { s1: song(a), s2: song(b), s3: song(c), s4: null };
  it('counts unmatched songs, keeps hand-added ones, removes only what the original dropped after a complete read', async () => {
    let listed = [{ id: 's1' }, { id: 's2' }, { id: 's4' }];
    let complete = true;
    const d = deps(() => [], () => ({ items: listed, complete }), matches);
    const first = await saveSpotifyPlaylist(d, 'P1', { title: 'Sp' });
    expect(first).toMatchObject({ created: true, added: 2, unmatched: 1 });
    const copy = (await bySource(spotifySourceId('P1')))!;
    await addTracks(copy.id, [song('h'.repeat(11))]); // added by hand
    // s2 left the original, s3 arrived; but the first read was short: nothing is removed
    listed = [{ id: 's1' }, { id: 's3' }];
    complete = false;
    expect(await saveSpotifyPlaylist(d, 'P1')).toMatchObject({ added: 1, removed: 0 });
    // a complete read now: s2 (a sync entry no longer listed) goes; the hand-added song stays
    listed = [{ id: 's1' }, { id: 's3' }];
    complete = true;
    expect(await saveSpotifyPlaylist(d, 'P1')).toMatchObject({ added: 0, removed: 1 });
    expect(await titles(copy.id)).toEqual([a, 'h'.repeat(11), c]);
  });
  it('a run where nothing matches changes nothing', async () => {
    const d = deps(() => [], () => ({ items: [{ id: 's1' }], complete: true }), matches);
    await saveSpotifyPlaylist(d, 'P2');
    const copy = (await bySource(spotifySourceId('P2')))!;
    const none = deps(() => [], () => ({ items: [{ id: 's1' }], complete: true }), {});
    expect(await saveSpotifyPlaylist(none, 'P2')).toEqual({ failed: 'None of the songs could be matched.' });
    expect(await titles(copy.id)).toEqual([a]);
  });
  it('resaveCopy routes by source id', async () => {
    const d = deps(() => [song(a)], () => ({ items: [{ id: 's1' }], complete: true }), matches);
    expect(await resaveCopy(d, 'PL5')).toMatchObject({ created: true });
    expect(await resaveCopy(d, spotifySourceId('P3'))).toMatchObject({ created: true });
  });
});
