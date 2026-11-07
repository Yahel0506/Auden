// Port of app/src/test/java/dev/auden/dj/DjTasteTest.kt. The genresAreRecognised case is in parser.test.ts,
// where namesGenre lives.
import { describe, expect, it } from 'vitest';
import { djTrack, type DjTrack } from './model';
import { Profile, merge, profile, queries, type Hit, type Query } from './taste';

const track = (id: string, artist: string, plays: number, genre: string | null = null, plays30 = 0): DjTrack =>
  djTrack({ id, title: id, artist, genre, plays, plays30 });

describe('profile', () => {
  it('the profile is the artists played most, and their genres', () => {
    const tracks: DjTrack[] = [
      track('1', 'Nsqk', 50, 'Hip Hop'),
      track('2', 'Héroes del Silencio', 30),
      track('3', 'Quiet Artist', 1),
      { ...track('4', 'Found', 99), discovery: true },
    ];
    const result = profile(tracks, (artist) => (artist === 'Héroes del Silencio' ? ['Rock'] : []));
    expect(result.artists).toEqual(['Nsqk', 'Héroes del Silencio', 'Quiet Artist']);
    // Both genres, the heavier first; a discovery is not the listener's taste.
    expect(result.genres).toEqual(['hip hop', 'rock']);
  });

  it('this month counts double', () => {
    const tracks = [track('1', 'Old', 10), track('2', 'Recent', 2, null, 5)];
    expect(profile(tracks, () => []).artists[0]).toBe('Recent');
  });
});

describe('queries', () => {
  it('an ask that names a genre is searched as it is', () => {
    const p = new Profile(['A', 'B'], ['rock', 'hip hop']);
    expect(queries('rock en español', p, true)).toEqual<Query[]>([{ type: 'plain', text: 'rock en español' }]);
  });

  it('otherwise it is searched in each genre and from each artist', () => {
    const p = new Profile(['A', 'B'], ['rock', 'hip hop']);
    const result = queries('música triste', p, false);
    expect(result.map((q) => q.text)).toEqual([
      'música triste rock',
      'música triste hip hop',
      'música triste A',
      'música triste B',
      'música triste',
    ]);
  });

  it('with nothing known about the listener it is searched as it is', () => {
    expect(queries('algo triste', new Profile([], []), false)).toHaveLength(1);
  });
});

describe('merge', () => {
  it('the genres take turns, and the listeners own artists come first', () => {
    const rock: Query = { type: 'inGenre', text: 'triste rock', genre: 'rock' };
    const hiphop: Query = { type: 'inGenre', text: 'triste hip hop', genre: 'hip hop' };
    const results: Array<readonly [Query, readonly Hit[]]> = [
      [rock, [{ id: 'r1', artist: 'Stranger' }, { id: 'r2', artist: 'Mine Rock' }]],
      [hiphop, [{ id: 'h1', artist: 'Stranger Two' }, { id: 'h2', artist: 'Mine Rap' }]],
    ];
    const ids = merge(results, new Set(['mine rock', 'mine rap']), 4);
    // One of each in turn; within each genre the listener's own artist before the stranger.
    expect(ids).toEqual(['r2', 'h2', 'r1', 'h1']);
  });

  it('an artist of theirs who fits joins whatever the genre', () => {
    const rock: Query = { type: 'inGenre', text: 'triste rock', genre: 'rock' };
    const artist: Query = { type: 'fromArtist', text: 'triste Nsqk', artist: 'Nsqk' };
    const results: Array<readonly [Query, readonly Hit[]]> = [
      [rock, [{ id: 'r1', artist: 'Someone' }]],
      // Only Nsqk's own songs count, whatever else the search returned.
      [artist, [{ id: 'n1', artist: 'Nsqk' }, { id: 'x', artist: 'Unrelated' }, { id: 'n2', artist: 'Nsqk' }]],
    ];
    const ids = merge(results, new Set(['nsqk']), 10);
    expect(ids).toContain('n1');
    expect(ids).toContain('n2');
    expect(ids).not.toContain('x');
  });

  it('the ask on its own only fills what is left, and no artist crowds the set', () => {
    const rock: Query = { type: 'inGenre', text: 'triste rock', genre: 'rock' };
    const plain: Query = { type: 'plain', text: 'triste' };
    const results: Array<readonly [Query, readonly Hit[]]> = [
      [rock, Array.from({ length: 5 }, (_, i) => ({ id: `r${i}`, artist: 'Same Band' }))],
      [plain, [{ id: 'p1', artist: 'Other' }, { id: 'p2', artist: 'Another' }]],
    ];
    const ids = merge(results, new Set(), 10);
    expect(ids.filter((id) => id.startsWith('r'))).toHaveLength(2);
    expect(ids.filter((id) => id.startsWith('p'))).toEqual(['p1', 'p2']);
  });
});
