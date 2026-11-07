import { describe, expect, it } from 'vitest';
import { type PlaylistEntry } from './files';
import { type MatchableTrack, PlaylistMatcher } from './matcher';

const track = (
  id: number,
  path: string | null,
  title: string,
  artist = 'Band',
  durationSec: number | null = null,
  videoId: string | null = null,
): MatchableTrack => ({ id, videoId, path, title, artist, durationSec });

const entry = (over: Partial<PlaylistEntry> = {}): PlaylistEntry => ({ path: null, ...over });

describe('PlaylistMatcher', () => {
  it('prefers exact path, then file name, then tags', () => {
    const library = [
      track(1, '/storage/sd/Rock/a.mp3', 'Song A', 'Band', 215),
      track(2, '/storage/sd/Pop/a.mp3', 'Other'),
      track(3, '/x/zzz.mp3', 'Café Déjà Vu (Remastered)', 'Artista'),
    ];
    const matcher = new PlaylistMatcher(library);
    expect(matcher.match(entry({ path: '/storage/sd/Rock/a.mp3' }))?.id).toBe(1);
    // Moved from another device: same name, the Rock folder shares the longer tail.
    expect(matcher.match(entry({ path: '/sdcard/Music/Rock/a.mp3' }))?.id).toBe(1);
    expect(matcher.match(entry({ path: '/gone.mp3', title: 'cafe deja vu', artist: 'Artista' }))?.id).toBe(3);
    expect(matcher.match(entry({ path: '/gone.mp3', title: 'Unknown', artist: 'x' }))).toBeNull();
  });

  it('rejects a title whose length is far off', () => {
    const matcher = new PlaylistMatcher([track(1, '/a.mp3', 'Intro', 'Band', 30)]);
    expect(matcher.match(entry({ title: 'Intro', artist: 'Band', durationSec: 300 }))).toBeNull();
  });

  it('accepts a title whose length is within two seconds', () => {
    const matcher = new PlaylistMatcher([track(1, '/a.mp3', 'Intro', 'Band', 30)]);
    expect(matcher.match(entry({ title: 'Intro', artist: 'Band', durationSec: 31.5 }))?.id).toBe(1);
  });

  it('matches a video id before anything else', () => {
    const matcher = new PlaylistMatcher([
      track(1, '/a.mp3', 'Same', 'Band', null, 'dQw4w9WgXcQ'),
      track(2, '/exact.mp3', 'Other'),
    ]);
    expect(matcher.match(entry({ path: '/exact.mp3', videoId: 'dQw4w9WgXcQ' }))?.id).toBe(1);
  });

  it('ignores a video id that is not in the library', () => {
    const matcher = new PlaylistMatcher([track(1, '/a.mp3', 'Song', 'Band', null, 'dQw4w9WgXcQ')]);
    expect(matcher.match(entry({ videoId: 'spotify:x', title: 'Other' }))).toBeNull();
  });

  it('does not treat a URL as a local path', () => {
    const matcher = new PlaylistMatcher([track(1, 'https://h/a.mp3', 'Stream')]);
    expect(matcher.match(entry({ path: 'https://h/a.mp3' }))).toBeNull();
  });

  it('matches paths regardless of case', () => {
    const matcher = new PlaylistMatcher([track(1, '/Music/Song.MP3', 'S')]);
    expect(matcher.match(entry({ path: '/music/song.mp3' }))?.id).toBe(1);
  });

  it('accepts an artist that contains the other after normalising', () => {
    const matcher = new PlaylistMatcher([track(1, '/a.mp3', 'Song', 'The Band, Guest')]);
    expect(matcher.match(entry({ title: 'song', artist: 'Band' }))?.id).toBe(1);
  });

  it('returns null without a title to compare', () => {
    const matcher = new PlaylistMatcher([track(1, '/a.mp3', '...')]);
    expect(matcher.match(entry({ title: '...' }))).toBeNull();
    expect(matcher.match(entry())).toBeNull();
  });
});
