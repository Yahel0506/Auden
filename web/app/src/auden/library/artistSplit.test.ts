import { describe, expect, it } from 'vitest';
import { ARTIST_KEEP_WHOLE, splitArtists } from './artistSplit';

// ArtistSplitterTest.kt, case for case.
describe('splitArtists', () => {
  it('splits on the usual separators', () => {
    expect(splitArtists('A & B, C')).toEqual(['A', 'B', 'C']);
    expect(splitArtists('A ft. B')).toEqual(['A', 'B']);
    expect(splitArtists('A FEAT. B')).toEqual(['A', 'B']);
    expect(splitArtists('A; B')).toEqual(['A', 'B']);
    expect(splitArtists('A // B')).toEqual(['A', 'B']);
  });

  it('keeps names that contain a separator', () => {
    expect(splitArtists('Simon & Garfunkel')).toEqual(['Simon & Garfunkel']);
    expect(splitArtists('Earth, Wind & Fire, Someone')).toEqual(['Earth, Wind & Fire', 'Someone']);
    expect(splitArtists('AC/DC')).toEqual(['AC/DC']);
  });

  it('keeps every keep-whole name whole', () => {
    for (const name of ARTIST_KEEP_WHOLE) {
      expect(splitArtists(name)).toEqual([name]);
      expect(splitArtists(`${name}, Someone`)).toEqual([name, 'Someone']);
    }
  });

  it('matches keep-whole names case-insensitively and keeps the credit spelling', () => {
    expect(splitArtists('simon & garfunkel')).toEqual(['simon & garfunkel']);
  });

  it('drops empties and duplicates', () => {
    expect(splitArtists('A, , B, a')).toEqual(['A', 'B']);
    expect(splitArtists('   ')).toEqual([]);
    expect(splitArtists('')).toEqual([]);
  });
});
