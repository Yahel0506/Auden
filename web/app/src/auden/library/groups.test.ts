import { describe, expect, it } from 'vitest';
import { albumsOf, artistsOf, directoryName, foldersOf, genresOf, type LocalSong } from './groups';

const song = (id: number, over: Partial<LocalSong> = {}): LocalSong => ({
  id,
  title: `T${id}`,
  artist: 'Artist',
  album: null,
  genre: null,
  folder: null,
  trackNumber: null,
  discNumber: null,
  ...over,
});

const ids = (songs: LocalSong[]) => songs.map((s) => s.id);

// LocalGroupsTest.kt, relevant assertions.
describe('artistsOf', () => {
  it('lists a collaboration under every artist on it, not as an artist of its own', () => {
    const songs = [
      song(1, { title: 'B', artist: 'A & B' }),
      song(2, { title: 'A', artist: 'A' }),
      song(3, { title: 'C', artist: 'Simon & Garfunkel' }),
    ];
    const groups = artistsOf(songs);
    expect(groups.map((g) => g.title)).toEqual(['A', 'B', 'Simon & Garfunkel']);
    expect(ids(groups[0]!.songs)).toEqual([2, 1]);
    expect(ids(groups[1]!.songs)).toEqual([1]);
    expect(groups[2]!.songs.map((s) => s.id)).toEqual([3]);
  });

  it('falls back to the raw artist when splitting leaves nothing', () => {
    const groups = artistsOf([song(1, { artist: 'AC/DC' })]);
    expect(groups.map((g) => g.key)).toEqual(['AC/DC']);
  });

  it('sorts artists case-insensitively', () => {
    const groups = artistsOf([song(1, { artist: 'beta' }), song(2, { artist: 'Alpha' }), song(3, { artist: 'gamma' })]);
    expect(groups.map((g) => g.title)).toEqual(['Alpha', 'beta', 'gamma']);
  });

  it('keeps the same artist name in one group, case-sensitively', () => {
    const groups = artistsOf([song(1, { artist: 'Bob' }), song(2, { artist: 'Bob' }), song(3, { artist: 'bob' })]);
    expect(groups.map((g) => [g.title, ids(g.songs)])).toEqual([
      ['Bob', [1, 2]],
      ['bob', [3]],
    ]);
  });
});

describe('albumsOf', () => {
  it('groups by exact album name and skips blank or missing names', () => {
    const songs = [
      song(1, { album: 'Live: At The Hall' }),
      song(2, { album: 'Other' }),
      song(3, { album: '   ' }),
      song(4, { album: null }),
      song(5, { album: 'Live: At The Hall' }),
      song(6, { album: 'live: at the hall' }),
    ];
    const groups = albumsOf(songs);
    expect(groups.map((g) => g.title)).toEqual(['Live: At The Hall', 'live: at the hall', 'Other']);
    expect(ids(groups[0]!.songs)).toEqual([1, 5]);
  });

  it('orders releases by disc, then track, then title, missing values last', () => {
    const songs = [
      song(1, { album: 'R', title: 'B', discNumber: 1, trackNumber: 2 }),
      song(2, { album: 'R', title: 'X', discNumber: 1, trackNumber: 1 }),
      song(3, { album: 'R', title: 'A', discNumber: 2, trackNumber: 1 }),
      song(4, { album: 'R', title: 'Z', discNumber: 1, trackNumber: null }),
      song(5, { album: 'R', title: 'Y', discNumber: 1, trackNumber: 2 }),
    ];
    const [group] = albumsOf(songs);
    expect(ids(group!.songs)).toEqual([2, 1, 5, 4, 3]);
  });

  it('sorts album groups by title', () => {
    const groups = albumsOf([song(1, { album: 'beta' }), song(2, { album: 'Alpha' })]);
    expect(groups.map((g) => g.title)).toEqual(['Alpha', 'beta']);
  });

  it('takes the subtitle from the first song in input order', () => {
    const [group] = albumsOf([song(1, { album: 'R', artist: 'First' }), song(2, { album: 'R', artist: 'Second' })]);
    expect(group!.subtitle).toBe('First');
  });
});

describe('genresOf', () => {
  it('groups tagged tracks and skips untagged ones', () => {
    const tracks = [
      song(1, { title: 'a', genre: 'Rock', folder: '/m' }),
      song(2, { title: 'b', genre: 'Rock', folder: '/m' }),
      song(3, { title: 'c', genre: null, folder: '/m' }),
      song(4, { title: 'd', genre: '  ' }),
    ];
    const groups = genresOf(tracks);
    expect(groups.map((g) => g.title)).toEqual(['Rock']);
    expect(ids(groups[0]!.songs)).toEqual([1, 2]);
  });

  it('keeps exact genre spelling', () => {
    expect(genresOf([song(1, { genre: 'Rock' }), song(2, { genre: 'rock' })]).map((g) => g.key)).toEqual(['Rock', 'rock']);
  });
});

describe('foldersOf', () => {
  it('keeps two folders of one name apart and tells them apart by subtitle', () => {
    const tracks = [
      song(1, { folder: '/sd/Rock/Hits' }),
      song(2, { folder: '/sd/Pop/Hits' }),
      song(3, { folder: null }),
    ];
    const groups = foldersOf(tracks);
    expect(groups.map((g) => [g.title, g.subtitle])).toEqual([
      ['Hits', 'sd/Pop'],
      ['Hits', 'sd/Rock'],
    ]);
    expect(ids(groups[0]!.songs)).toEqual([2]);
  });

  it('drops the volume from phone storage paths and keeps a memory card name', () => {
    const tracks = [
      song(1, { title: 'B', folder: '/storage/emulated/0/Music/Rock' }),
      song(2, { title: 'A', folder: '/storage/emulated/0/Music/Rock' }),
      song(3, { title: 'C', folder: '/storage/1A2B-3C4D/Music' }),
      song(4, { title: 'D', folder: '/storage/emulated/0/Download' }),
      song(5, { title: 'X', folder: null }),
    ];
    const folders = foldersOf(tracks);
    expect(folders.map((f) => f.title)).toEqual(['Download', 'Music', 'Rock']);
    expect(folders.map((f) => f.subtitle)).toEqual(['', '1A2B-3C4D', 'Music']);
    const rock = folders[2];
    expect(rock!.key).toBe('/storage/emulated/0/Music/Rock');
    expect(ids(rock!.songs)).toEqual([2, 1]);
  });

  it('sorts folders by title, then subtitle', () => {
    const groups = foldersOf([
      song(1, { folder: '/z/Same' }),
      song(2, { folder: '/a/Same' }),
      song(3, { folder: '/m/alpha' }),
    ]);
    expect(groups.map((g) => g.key)).toEqual(['/m/alpha', '/a/Same', '/z/Same']);
  });
});

describe('directoryName', () => {
  it('is the last path segment, ignoring trailing slashes', () => {
    expect(directoryName('/sd/Pop/Hits/')).toBe('Hits');
    expect(directoryName('Hits')).toBe('Hits');
  });
});
