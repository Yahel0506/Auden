// Port of the parser tests in app/src/test/java/dev/auden/dj/DjTest.kt, and genresAreRecognised from DjTasteTest.kt.
import { describe, expect, it } from 'vitest';
import { DjMood, djTrack, understood, type DjTrack } from './model';
import { namesGenre, parseRequest } from './parser';

const t = (id: string, artist: string, genre: string | null, year: number | null, plays: number, plays30 = 0): DjTrack =>
  djTrack({ id, title: `T${id}`, artist, album: null, genre, year, plays, plays30, lastPlayedAt: 0, addedAt: 0 });

/** Ana (rock, 90s), Bo (pop, 2000s), Cy (jazz, 90s): the same library the planner tests use. */
const library: DjTrack[] = [
  ...[1, 2, 3, 4, 5, 6].map((i) => t(`a${i}`, 'Ana', 'Rock', 1990 + i, 10 - i, 2)),
  ...[1, 2, 3, 4, 5, 6].map((i) => t(`b${i}`, 'Bo', 'Pop', 2000 + i, 5, 1)),
  ...[1, 2, 3, 4, 5, 6].map((i) => t(`c${i}`, 'Cy', 'Jazz', 1990 + i, 3)),
];

describe('parseRequest', () => {
  it('the request is read against the library', () => {
    const r = parseRequest('algo tranquilo de los 90 de Cy', library);
    expect(r.artist).toBe('Cy');
    expect(r.decade).toBe(1990);
    expect(r.mood).toBe(DjMood.CHILL);
    expect(understood(r)).toBe(true);
  });

  it('a bare number is not a decade', () => {
    expect(parseRequest('pon 10 canciones', library).decade ?? null).toBeNull();
    expect(parseRequest('hits of the 10s', library).decade).toBe(2010);
    expect(parseRequest('los ochenta', library).decade).toBe(1980);
    // Kotlin's 1985 / 10 * 10 is integer division: 1980.
    expect(parseRequest('de 1985', library).decade).toBe(Math.trunc(1985 / 10) * 10);
  });

  it('an unintelligible request is not understood', () => {
    expect(understood(parseRequest('qwerty zxcv', library))).toBe(false);
  });

});

describe('namesGenre', () => {
  it('genres are recognised in the ask, and moods are not', () => {
    expect(namesGenre('rock en español')).toBe(true);
    expect(namesGenre('algo de hip hop')).toBe(true);
    expect(namesGenre('Reggaetón para bailar')).toBe(true);
    expect(namesGenre('música triste')).toBe(false);
    expect(namesGenre('algo para concentrarme')).toBe(false);
    // "pop" inside another word is not the genre.
    expect(namesGenre('canciones populares')).toBe(false);
  });
});
