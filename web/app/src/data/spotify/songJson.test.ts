import { describe, expect, it } from 'vitest';
import type { Track } from '@/data/music/types';
import { storedJsonToTrack, trackToStoredJson } from './songJson';

/** A stored string as SpotifyMatches.kt writes it, with an extra key Android does not model. */
const ANDROID_JSON = JSON.stringify({
  videoId: 'vid123',
  title: 'Get Lucky',
  artist: 'Daft Punk, Pharrell Williams',
  thumbnailUrl: 'https://i.ytimg.com/vi/vid123/hq.jpg',
  durationText: '1:02:03',
  albumName: 'Random Access Memories',
  artistId: 'UCdaft',
  albumId: 'MPREb_ram',
  isVideo: true,
  isExplicit: true,
  playCount: 42,
});

const TYPICAL: Track = {
  videoId: 'abc',
  title: 'Instant Crush',
  artists: [
    { name: 'Daft Punk', id: 'UC1' },
    { name: 'Julian Casablancas', id: 'UC2' },
  ],
  album: { name: 'Random Access Memories', id: 'MPREb_1' },
  durationSec: 337,
  explicit: false,
  thumbnail: 'https://i.ytimg.com/vi/abc/hq.jpg',
  index: 3,
  plays: '19M plays',
};

function stored(obj: Record<string, unknown>): string {
  return JSON.stringify({ videoId: 'v', title: 't', artist: 'A', isVideo: false, ...obj });
}

describe('storedJsonToTrack', () => {
  it('decodes an Android-shaped song', () => {
    expect(storedJsonToTrack(ANDROID_JSON)).toEqual<Track>({
      videoId: 'vid123',
      title: 'Get Lucky',
      artists: [
        { name: 'Daft Punk', id: 'UCdaft' },
        { name: 'Pharrell Williams', id: null },
      ],
      album: { name: 'Random Access Memories', id: 'MPREb_ram' },
      durationSec: 3723,
      explicit: true,
      thumbnail: 'https://i.ytimg.com/vi/vid123/hq.jpg',
      index: null,
      plays: null,
    });
  });

  it('gives only the first artist the channel id', () => {
    const track = storedJsonToTrack(stored({ artist: 'A & B, C', artistId: 'UCa' }));
    expect(track?.artists).toEqual([
      { name: 'A', id: 'UCa' },
      { name: 'B', id: null },
      { name: 'C', id: null },
    ]);
  });

  it('reads "m:ss" and "h:mm:ss" durations and rejects other shapes', () => {
    expect(storedJsonToTrack(stored({ durationText: '0:59' }))?.durationSec).toBe(59);
    expect(storedJsonToTrack(stored({ durationText: '10:00' }))?.durationSec).toBe(600);
    expect(storedJsonToTrack(stored({ durationText: '1:02:03' }))?.durationSec).toBe(3723);
    expect(storedJsonToTrack(stored({ durationText: '1:75' }))?.durationSec).toBeNull();
    expect(storedJsonToTrack(stored({ durationText: '3:45 min' }))?.durationSec).toBeNull();
    expect(storedJsonToTrack(stored({ durationText: '0:00' }))?.durationSec).toBeNull();
    expect(storedJsonToTrack(stored({}))?.durationSec).toBeNull();
  });

  it('defaults explicit to false and leaves the rest empty when absent', () => {
    expect(storedJsonToTrack(stored({}))).toEqual<Track>({
      videoId: 'v',
      title: 't',
      artists: [{ name: 'A', id: null }],
      album: null,
      durationSec: null,
      explicit: false,
      thumbnail: null,
      index: null,
      plays: null,
    });
  });

  it('returns null for invalid JSON, non-objects, or a missing videoId or title', () => {
    expect(storedJsonToTrack('not json')).toBeNull();
    expect(storedJsonToTrack('')).toBeNull();
    expect(storedJsonToTrack('null')).toBeNull();
    expect(storedJsonToTrack('[]')).toBeNull();
    expect(storedJsonToTrack(JSON.stringify({ title: 't', artist: 'A' }))).toBeNull();
    expect(storedJsonToTrack(JSON.stringify({ videoId: 'v', artist: 'A' }))).toBeNull();
    expect(storedJsonToTrack(JSON.stringify({ videoId: 7, title: 't' }))).toBeNull();
  });
});

describe('trackToStoredJson', () => {
  it('always writes a boolean isVideo', () => {
    expect(JSON.parse(trackToStoredJson(TYPICAL)).isVideo).toBe(false);
    expect(JSON.parse(trackToStoredJson({ ...TYPICAL, artists: [] })).isVideo).toBe(false);
  });

  it('omits null optionals instead of writing null', () => {
    const json = trackToStoredJson({
      ...TYPICAL,
      artists: [],
      album: null,
      durationSec: null,
      thumbnail: null,
    });
    const keys = Object.keys(JSON.parse(json));
    expect(keys).not.toContain('thumbnailUrl');
    expect(keys).not.toContain('durationText');
    expect(keys).not.toContain('albumName');
    expect(keys).not.toContain('albumId');
    expect(keys).not.toContain('artistId');
    expect(JSON.parse(json)).toEqual({
      videoId: 'abc',
      title: 'Instant Crush',
      artist: '',
      isVideo: false,
      isExplicit: false,
    });
  });

  it('joins credits with ", " and writes the lead artist id', () => {
    const parsed = JSON.parse(trackToStoredJson(TYPICAL));
    expect(parsed.artist).toBe('Daft Punk, Julian Casablancas');
    expect(parsed.artistId).toBe('UC1');
    expect(parsed.albumName).toBe('Random Access Memories');
    expect(parsed.albumId).toBe('MPREb_1');
    expect(parsed.thumbnailUrl).toBe('https://i.ytimg.com/vi/abc/hq.jpg');
    expect(parsed.isExplicit).toBe(false);
  });

  it('formats durations as "m:ss" or "h:mm:ss"', () => {
    const durationOf = (sec: number) => JSON.parse(trackToStoredJson({ ...TYPICAL, durationSec: sec })).durationText;
    expect(durationOf(59)).toBe('0:59');
    expect(durationOf(600)).toBe('10:00');
    expect(durationOf(3723)).toBe('1:02:03');
    expect(durationOf(337)).toBe('5:37');
  });
});

describe('round trip', () => {
  it('keeps the fields a stored match carries', () => {
    const back = storedJsonToTrack(trackToStoredJson(TYPICAL));
    expect(back).toEqual<Track>({
      videoId: 'abc',
      title: 'Instant Crush',
      artists: [
        { name: 'Daft Punk', id: 'UC1' },
        { name: 'Julian Casablancas', id: null },
      ],
      album: { name: 'Random Access Memories', id: 'MPREb_1' },
      durationSec: 337,
      explicit: false,
      thumbnail: 'https://i.ytimg.com/vi/abc/hq.jpg',
      index: null,
      plays: null,
    });
  });

  it('reads a long track the way Android writes it: minutes past 59 in the m:ss form', () => {
    const decoded = (durationText: string) => storedJsonToTrack(JSON.stringify({ videoId: 'v', title: 't', artist: 'a', isVideo: false, durationText }))?.durationSec;
    expect(decoded('75:03')).toBe(75 * 60 + 3);
    expect(decoded('1:02:03')).toBe(3723);
    expect(decoded('1:75:03')).toBeNull();
    expect(decoded('3:75')).toBeNull();
  });
});
