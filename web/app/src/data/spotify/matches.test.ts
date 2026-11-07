// @vitest-environment happy-dom
import 'fake-indexeddb/auto';
import { IDBFactory } from 'fake-indexeddb';
import type { SpotifyTrack } from '@bitchord/shared/bridge';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { closeAudenDb, openAudenDb } from '@/auden/db/schema';
import type { Track } from '@/data/music/types';

// The search and the matcher's scoring are not what is under test here.
vi.mock('@/data/music/api', () => ({ searchSongs: vi.fn() }));

const { MatchStatus, onManualMatch, resolveSpotifyTrack, RETRY_MISSING_MS, setManualMatch } = await import('./matches');
const { storedJsonToTrack, trackToStoredJson } = await import('./songJson');

const spotify: SpotifyTrack = { id: 'sp1', title: 'Hello', artist: 'Adele', album: '25', durationMs: 295_000, imageUrl: 'https://img/sp.jpg' };
const yt = (videoId: string, title = 'Hello'): Track => ({
  videoId,
  title,
  artists: [{ name: 'Adele', id: 'UCadele' }],
  album: { name: '25', id: 'MPREb_25' },
  durationSec: 295,
  explicit: false,
  thumbnail: null,
  index: null,
  plays: null,
});

const T0 = 1_700_000_000_000;
let now = T0;
let hits: Track[] = [];
const search = vi.fn(async () => hits);
const deps = () => ({ search, now: () => now });

beforeEach(() => {
  globalThis.indexedDB = new IDBFactory();
  now = T0;
  hits = [yt('vid1')];
  search.mockClear();
});
afterEach(async () => {
  await closeAudenDb();
});

describe('resolveSpotifyTrack', () => {
  it('searches once, remembers the song, and answers from memory after that', async () => {
    const first = await resolveSpotifyTrack(spotify, deps());
    expect(first).toMatchObject({ status: 'found', manual: false, track: { videoId: 'vid1' } });
    hits = [yt('somethingElse')];
    const second = await resolveSpotifyTrack(spotify, deps());
    expect(second).toMatchObject({ status: 'found', track: { videoId: 'vid1' } });
    expect(search).toHaveBeenCalledTimes(1);
    const row = await (await openAudenDb()).get('spotify_match', 'sp1');
    expect(row).toMatchObject({ status: MatchStatus.AUTO, matchedAt: T0 });
  });

  it('a found song is kept for good, however long ago', async () => {
    await resolveSpotifyTrack(spotify, deps());
    now += 400 * 24 * 60 * 60 * 1000;
    await resolveSpotifyTrack(spotify, deps());
    expect(search).toHaveBeenCalledTimes(1);
  });

  it('remembers "not found" for three days, then looks again', async () => {
    hits = [];
    expect(await resolveSpotifyTrack(spotify, deps())).toEqual({ status: 'missing' });
    now += RETRY_MISSING_MS - 1000;
    expect(await resolveSpotifyTrack(spotify, deps())).toEqual({ status: 'missing' });
    expect(search).toHaveBeenCalledTimes(1);
    now += 2000;
    hits = [yt('lateArrival')];
    expect(await resolveSpotifyTrack(spotify, deps())).toMatchObject({ status: 'found', track: { videoId: 'lateArrival' } });
    expect(search).toHaveBeenCalledTimes(2);
  });

  it('does not remember a search that failed: the next open asks again', async () => {
    search.mockRejectedValueOnce(new Error('offline'));
    const failed = await resolveSpotifyTrack(spotify, deps());
    expect(failed).toMatchObject({ status: 'error' });
    expect(await (await openAudenDb()).get('spotify_match', 'sp1')).toBeUndefined();
    expect(await resolveSpotifyTrack(spotify, deps())).toMatchObject({ status: 'found' });
  });

  it('falls back to the search\'s first hit when nothing scores, and uses Spotify\'s cover when it has none', async () => {
    hits = [yt('firstHit', 'A Completely Different Song')];
    const result = await resolveSpotifyTrack(spotify, deps());
    expect(result).toMatchObject({ status: 'found', track: { videoId: 'firstHit', thumbnail: 'https://img/sp.jpg' } });
  });
});

describe('a hand-picked match', () => {
  it('is never replaced by a search', async () => {
    await setManualMatch('sp1', yt('picked'));
    hits = [yt('autoHit')];
    const result = await resolveSpotifyTrack(spotify, deps());
    expect(result).toMatchObject({ status: 'found', manual: true, track: { videoId: 'picked' } });
    expect(search).not.toHaveBeenCalled();
    expect((await (await openAudenDb()).get('spotify_match', 'sp1'))?.status).toBe(MatchStatus.MANUAL);
  });

  it('wins over a lookup that was already in flight when it was made', async () => {
    let release!: (tracks: Track[]) => void;
    search.mockImplementationOnce(() => new Promise<Track[]>((resolve) => (release = resolve)));
    const inFlight = resolveSpotifyTrack(spotify, deps());
    await vi.waitFor(() => expect(search).toHaveBeenCalled());
    await setManualMatch('sp1', yt('picked'));
    release([yt('autoHit')]);
    // What comes back is what is stored: the pick, not the lookup's answer.
    expect(await inFlight).toMatchObject({ status: 'found', manual: true, track: { videoId: 'picked' } });
    expect((await (await openAudenDb()).get('spotify_match', 'sp1'))?.songJson).toContain('picked');
  });

  it('tells whoever listens, so rows already on screen are replaced', async () => {
    const heard = vi.fn();
    const off = onManualMatch(heard);
    await setManualMatch('sp1', yt('picked'));
    off();
    expect(heard).toHaveBeenCalledWith('sp1', expect.objectContaining({ videoId: 'picked' }));
  });
});

describe('what is stored', () => {
  it('is read the way Android reads it: a row with a song is found, whatever its status', async () => {
    const db = await openAudenDb();
    await db.put('spotify_match', { spotifyId: 'sp1', status: 'SOMETHING_NEW', songJson: trackToStoredJson(yt('fromBackup')), matchedAt: 1 });
    expect(await resolveSpotifyTrack(spotify, deps())).toMatchObject({ status: 'found', track: { videoId: 'fromBackup' } });
    expect(search).not.toHaveBeenCalled();
  });

  it('writes a song Android can read back (golden check on the stored JSON)', async () => {
    await resolveSpotifyTrack(spotify, deps());
    const json = JSON.parse((await (await openAudenDb()).get('spotify_match', 'sp1'))!.songJson!);
    expect(json).toMatchObject({ videoId: 'vid1', title: 'Hello', artist: 'Adele', artistId: 'UCadele', albumName: '25', albumId: 'MPREb_25', durationText: '4:55', isVideo: false });
    expect(typeof json.isVideo).toBe('boolean');
    expect(storedJsonToTrack(JSON.stringify(json))?.videoId).toBe('vid1');
  });
});
