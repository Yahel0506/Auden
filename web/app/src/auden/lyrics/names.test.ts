import { describe, expect, it } from 'vitest';
import { LYRICS_SOURCES } from '@/lyrics/sources';
import { fromStoredProvider, storedProviderLabel, toStoredProvider } from './names';

describe('stored lyrics provider names', () => {
  it('writes each web source under its Android LyricsSource name', () => {
    expect(LYRICS_SOURCES.map((s) => [s.id, toStoredProvider(s.id)])).toEqual([
      ['lrc-red', 'LRC_RED'],
      ['bini', 'BINI_LYRICS'],
      ['better-lyrics', 'BETTER_LYRICS'],
      ['better-lyrics-portato', 'BETTER_LYRICS_PORTATO'],
      ['paxsenix', 'PAXSENIX'],
      ['paxsenix-spotify', 'PAXSENIX_SPOTIFY'],
      ['paxsenix-musixmatch', 'PAXSENIX_MUSIXMATCH'],
      ['lyrics-plus', 'LYRICS_PLUS'],
      ['unison', 'UNISON'],
      ['youtube-captions', 'YOUTUBE_TRANSCRIPT'],
      ['youtube-music', 'YOUTUBE_MUSIC'],
      ['kugou', 'KUGOU'],
      ['lrclib', 'LRCLIB'],
      ['musixmatch', 'MUSIXMATCH'],
      ['genius', 'GENIUS'],
    ]);
  });

  it('round-trips every web source', () => {
    for (const source of LYRICS_SOURCES) {
      expect(fromStoredProvider(toStoredProvider(source.id))).toBe(source.id);
    }
  });

  it('returns null for Android-only names the web does not offer', () => {
    expect(fromStoredProvider('SIMP_MUSIC')).toBeNull();
    expect(fromStoredProvider('MEGALOBIZ')).toBeNull();
    expect(fromStoredProvider('NOT_A_SOURCE')).toBeNull();
  });

  it('labels a known name with the web label', () => {
    expect(storedProviderLabel('YOUTUBE_TRANSCRIPT')).toBe('YouTube captions');
    expect(storedProviderLabel('BETTER_LYRICS_PORTATO')).toBe('BetterLyrics Portato');
  });

  it('title-cases an unknown name for its label', () => {
    expect(storedProviderLabel('SIMP_MUSIC')).toBe('Simp Music');
    expect(storedProviderLabel('MEGALOBIZ')).toBe('Megalobiz');
  });
});
