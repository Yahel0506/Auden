import 'fake-indexeddb/auto';
import { IDBFactory } from 'fake-indexeddb';
import { afterEach, beforeEach, describe, expect, it } from 'vitest';
import { closeAudenDb, openAudenDb, type StoredBucket } from '../db/schema';
import { mergeBucket, record, MAX_TRACKS, type Pending, type SongInfo } from './bucket';
import { albumKey, primaryArtist } from './credit';
import { flushListening, listeningMonths } from './listening';
import { MAX_STEP_MS, PLAY_CEILING_MS, PLAY_FLOOR_MS, PlayRecorder } from './playRecorder';

const song = (over: Partial<SongInfo> = {}): SongInfo => ({
  videoId: 'v1',
  title: 'Song',
  artist: 'Cheema Y & Gur Sidhu',
  albumName: 'Album',
  albumId: null,
  artistId: null,
  thumbnailUrl: null,
  ...over,
});
const at = (day: number, hour = 12) => new Date(2026, 10, day, hour, 0, 0);

beforeEach(() => {
  globalThis.indexedDB = new IDBFactory();
});
afterEach(async () => {
  await closeAudenDb();
});

describe('credit', () => {
  it('takes the lead artist, splitting on punctuation and feature markers only', () => {
    expect(primaryArtist('Cheema Y & Gur Sidhu')).toBe('Cheema Y');
    expect(primaryArtist('A, B')).toBe('A');
    expect(primaryArtist('A feat. B')).toBe('A');
    expect(primaryArtist('A x B')).toBe('A');
    expect(primaryArtist('Florence and the Machine')).toBe('Florence and the Machine');
    expect(primaryArtist('  ')).toBeNull();
  });

  it('keys an album by id, else by name and artist with a separator no title holds', () => {
    expect(albumKey('MPREb_x', 'A', 'B')).toBe('MPREb_x');
    expect(albumKey(null, 'Greatest Hits', 'Vol 2')).not.toBe(albumKey(null, 'Greatest Hits Vol', '2'));
    expect(albumKey(null, 'Album', 'ARTIST')).toBe(albumKey(null, 'album', 'artist'));
  });
});

describe('record', () => {
  it('files the lead artist once, not each collaboration', () => {
    const pending: Pending = new Map();
    record(pending, song(), 5000, false, at(5));
    record(pending, song({ videoId: 'v2', artist: 'Cheema Y' }), 3000, true, at(5));
    const b = pending.get('2026-11')!;
    expect([...b.artists.keys()]).toEqual(['cheema y']);
    expect(b.artists.get('cheema y')).toMatchObject({ ms: 8000, plays: 1 });
    expect(b.tracks.get('v1')).toMatchObject({ ms: 5000, plays: 0 });
    expect(b.hours[12]).toBe(8000);
    expect(b.days[5]).toBe(8000);
    expect(b.trackDays['2026-11-05']).toEqual({ ms: 8000, plays: 1 });
  });

  it('fills in an album learned after the track started', () => {
    const pending: Pending = new Map();
    record(pending, song({ albumName: null }), 1000, false, at(5));
    record(pending, song({ albumName: 'Late', albumId: 'MPREb_1' }), 1000, false, at(5));
    const t = pending.get('2026-11')!.tracks.get('v1')!;
    expect(t).toMatchObject({ album: 'Late', albumId: 'MPREb_1' });
  });

  it('ignores silence and blank ids, and splits months', () => {
    const pending: Pending = new Map();
    record(pending, song(), 0, false, at(5));
    record(pending, song({ videoId: ' ' }), 1000, false, at(5));
    expect(pending.size).toBe(0);
    record(pending, song(), 1000, false, new Date(2026, 11, 1));
    expect([...pending.keys()]).toEqual(['2026-12']);
  });
});

describe('merge', () => {
  // Written by hand in the shape ListeningStats.kt serializes: arrays of entries, days keyed by number.
  const android: StoredBucket = {
    version: 1,
    month: '2026-11',
    tracks: [
      { id: 'v1', title: 'Song', artist: 'Cheema Y & Gur Sidhu', album: 'Album', albumId: null, artistId: null, art: null, ms: 60000, plays: 2, last: 1, days: { '2026-11-04': { ms: 60000, plays: 2 } } },
    ],
    artists: [{ name: 'Cheema Y', sub: null, art: null, id: null, ms: 60000, plays: 2, key: null, days: { '2026-11-04': { ms: 60000, plays: 2 } } }],
    albums: [{ name: 'Album', sub: 'Cheema Y & Gur Sidhu', art: null, id: null, ms: 60000, plays: 2, key: null, days: {} }],
    hours: Array.from({ length: 24 }, (_, h) => (h === 9 ? 60000 : 0)),
    days: { 4: 60000 },
    trackDays: { '2026-11-04': { ms: 60000, plays: 2 } },
    artistDays: null,
    albumDays: null,
  };

  it('adds new listening to a bucket the phone wrote, entry by entry', () => {
    const pending: Pending = new Map();
    record(pending, song(), 5000, true, at(5, 9));
    const merged = mergeBucket(android, pending.get('2026-11')!);
    expect(merged.tracks).toHaveLength(1);
    expect(merged.tracks[0]).toMatchObject({ ms: 65000, plays: 3 });
    expect(merged.tracks[0]!.days['2026-11-05']).toEqual({ ms: 5000, plays: 1 });
    expect(merged.artists).toHaveLength(1);
    expect(merged.albums).toHaveLength(1);
    expect(merged.hours[9]).toBe(65000);
    expect(merged.days).toEqual({ 4: 60000, 5: 5000 });
    expect(merged.trackDays!['2026-11-04']).toEqual({ ms: 60000, plays: 2 });
    // The input is not mutated.
    expect(android.tracks[0]!.ms).toBe(60000);
  });

  it('adds entries that land on one key rather than overwriting', () => {
    const dup: StoredBucket = {
      ...android,
      artists: [...android.artists, { ...android.artists[0]!, name: 'cheema y', ms: 1000, plays: 0 }],
    };
    const pending: Pending = new Map();
    record(pending, song({ videoId: 'v9', artist: 'Someone Else' }), 1000, false, at(5));
    const merged = mergeBucket(dup, pending.get('2026-11')!);
    expect(merged.artists.find((a) => a.name === 'Cheema Y')).toMatchObject({ ms: 61000, plays: 2 });
    expect(merged.artists).toHaveLength(2);
  });

  it('keeps the tracks listened to longest when a bucket overflows', () => {
    const pending: Pending = new Map();
    for (let i = 0; i < MAX_TRACKS + 5; i++) record(pending, song({ videoId: `t${i}` }), 1000 + i, false, at(5));
    const merged = mergeBucket(undefined, pending.get('2026-11')!);
    expect(merged.tracks).toHaveLength(MAX_TRACKS);
    expect(merged.tracks.some((t) => t.id === 't0')).toBe(false);
    expect(merged.tracks.some((t) => t.id === `t${MAX_TRACKS + 4}`)).toBe(true);
  });
});

describe('PlayRecorder', () => {
  const wall = at(5);
  const play = (r: PlayRecorder, id: string, from: number, steps: number, step = 5000, duration = 200_000) => {
    for (let i = 0; i <= steps; i++) r.onSample(song({ videoId: id }), duration, from + i * step, wall);
    return from + steps * step;
  };
  const stats = (r: PlayRecorder, id = 'v1') => r.pending.get('2026-11')?.tracks.get(id);

  it('the first sample only anchors; every later one adds its step', () => {
    const r = new PlayRecorder();
    r.onSample(song(), 200_000, 0, wall);
    expect(stats(r)).toBeUndefined();
    r.onSample(song(), 200_000, 5000, wall);
    expect(stats(r)).toMatchObject({ ms: 5000, plays: 0 });
  });

  it('caps a step so a throttled tab cannot credit an hour', () => {
    const r = new PlayRecorder();
    r.onSample(song(), 200_000, 0, wall);
    r.onSample(song(), 200_000, 3_600_000, wall);
    expect(stats(r)!.ms).toBe(MAX_STEP_MS);
  });

  it('counts a play at the thirty second floor when the track is short', () => {
    const r = new PlayRecorder();
    play(r, 'v1', 0, 5, 5000, 40_000); // 25 s: below the floor
    expect(stats(r)!.plays).toBe(0);
    play(r, 'v1', 25_000, 1, 5000, 40_000); // exactly 30 s
    expect(stats(r)!.plays).toBe(1);
  });

  it('needs half a mid-length track, and counts once', () => {
    const r = new PlayRecorder();
    const end = play(r, 'v1', 0, 19, 5000, 200_000); // 95 s of 100 s needed
    expect(stats(r)!.plays).toBe(0);
    play(r, 'v1', end, 2, 5000, 200_000);
    expect(stats(r)!.plays).toBe(1);
    play(r, 'v1', end + 10_000, 20, 5000, 200_000);
    expect(stats(r)!.plays).toBe(1);
  });

  it('never asks more than four minutes of a long track', () => {
    const r = new PlayRecorder();
    const steps = PLAY_CEILING_MS / 5000;
    play(r, 'v1', 0, steps, 5000, 3_600_000);
    expect(stats(r)!.plays).toBe(1);
  });

  it('uses the floor when the length is unknown', () => {
    const r = new PlayRecorder();
    play(r, 'v1', 0, PLAY_FLOOR_MS / 5000, 5000, 0);
    expect(stats(r)!.plays).toBe(1);
  });

  it('a pause drops the clock but keeps progress: no double count, no credited pause', () => {
    const r = new PlayRecorder();
    const end = play(r, 'v1', 0, 6, 5000, 40_000); // 30 s listened: counted
    expect(stats(r)!.plays).toBe(1);
    r.onPause();
    r.onSample(song(), 40_000, end + 600_000, wall); // resumes ten minutes later: anchors only
    expect(stats(r)!.ms).toBe(30_000);
    r.onSample(song(), 40_000, end + 605_000, wall);
    expect(stats(r)).toMatchObject({ ms: 35_000, plays: 1 });
  });

  it('repeat-one counts a second play once the track has ended in between', () => {
    const r = new PlayRecorder();
    const end = play(r, 'v1', 0, 6, 5000, 40_000);
    r.onTrackEnded();
    r.onTrackEnded(); // idempotent: ended and the id change both fire
    play(r, 'v1', end + 1000, 7, 5000, 40_000);
    expect(stats(r)!.plays).toBe(2);
  });

  it('a new track resets progress and anchors', () => {
    const r = new PlayRecorder();
    play(r, 'v1', 0, 3, 5000, 40_000);
    r.onSample(song({ videoId: 'v2' }), 40_000, 20_000, wall);
    expect(stats(r, 'v2')).toBeUndefined();
    r.onSample(song({ videoId: 'v2' }), 40_000, 25_000, wall);
    expect(stats(r, 'v2')!.ms).toBe(5000);
    expect(stats(r, 'v1')!.ms).toBe(15_000);
  });

  it('take() hands over the collection and starts a new one', () => {
    const r = new PlayRecorder();
    play(r, 'v1', 0, 2);
    const taken = r.take();
    expect(taken.size).toBe(1);
    expect(r.pending.size).toBe(0);
  });
});

describe('flushListening', () => {
  const batch = (ms: number) => {
    const p: Pending = new Map();
    record(p, song(), ms, false, at(5));
    return p;
  };

  it('writes one bucket per month and adds to it on the next flush', async () => {
    await flushListening(batch(1000));
    await flushListening(batch(2000));
    const months = await listeningMonths();
    expect(months).toHaveLength(1);
    expect(months[0]!.tracks[0]!.ms).toBe(3000);
  });

  it('two flushes at once both land', async () => {
    await Promise.all([flushListening(batch(1000)), flushListening(batch(2000)), flushListening(batch(4000))]);
    expect((await listeningMonths())[0]!.tracks[0]!.ms).toBe(7000);
  });

  it('drops months past three years', async () => {
    const db = await openAudenDb();
    for (let m = 0; m < 38; m++) {
      const month = `${2023 + Math.floor(m / 12)}-${String((m % 12) + 1).padStart(2, '0')}`;
      await db.put('listening', { version: 1, month, tracks: [], artists: [], albums: [], hours: new Array<number>(24).fill(0), days: {}, trackDays: null, artistDays: null, albumDays: null });
    }
    await flushListening(batch(1000));
    expect((await db.getAllKeys('listening')).length).toBeLessThanOrEqual(36);
  });
});
