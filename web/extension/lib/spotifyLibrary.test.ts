import { describe, expect, it } from 'vitest';
import { libraryVariables, parseAlbumPage, parseArtistPage } from './spotifyLibrary';

// Fixtures are the JSON strings of SpotifyLibraryTest.kt, read with JSON.parse the same way.
describe('spotify library pages', () => {
  it('albums read name, artist and the widest cover', () => {
    const root = JSON.parse(`
      {"data":{"me":{"libraryV3":{"totalCount":2,"items":[
        {"item":{"__typename":"AlbumResponseWrapper","_uri":"spotify:album:al1","data":{
          "__typename":"Album","name":"Record",
          "artists":{"items":[{"profile":{"name":"Band"}},{"profile":{"name":"Guest"}}]},
          "coverArt":{"sources":[{"url":"https://small","width":64},{"url":"https://big","width":640}]}
        }}},
        {"item":{"__typename":"PlaylistResponseWrapper","_uri":"spotify:playlist:p","data":{"__typename":"Playlist","name":"Not an album"}}}
      ]}}}}
    `);
    const page = parseAlbumPage(root);
    expect(page.total).toBe(2);
    expect(page.raw).toBe(2);
    expect(page.items).toHaveLength(1);
    expect(page.items[0]).toEqual({
      id: 'al1',
      name: 'Record',
      artist: 'Band, Guest',
      imageUrl: 'https://big',
    });
  });

  it('artists read name and avatar', () => {
    const root = JSON.parse(`
      {"data":{"me":{"libraryV3":{"totalCount":1,"items":[
        {"item":{"__typename":"ArtistResponseWrapper","_uri":"spotify:artist:ar1","data":{
          "__typename":"Artist","profile":{"name":"Singer"},
          "visuals":{"avatarImage":{"sources":[{"url":"https://face","width":320}]}}
        }}}
      ]}}}}
    `);
    const page = parseArtistPage(root);
    expect(page.items).toEqual([{ id: 'ar1', name: 'Singer', imageUrl: 'https://face' }]);
    expect(page.total).toBe(1);
    expect(page.raw).toBe(1);
  });

  it('artists count entries of other kinds in raw but not in items', () => {
    const root = JSON.parse(`
      {"data":{"me":{"libraryV3":{"totalCount":2,"items":[
        {"item":{"__typename":"ArtistResponseWrapper","_uri":"spotify:artist:ar1","data":{
          "__typename":"Artist","profile":{"name":"Singer"}}}},
        {"item":{"__typename":"AlbumResponseWrapper","_uri":"spotify:album:al1","data":{
          "__typename":"Album","name":"Record"}}}
      ]}}}}
    `);
    const page = parseArtistPage(root);
    expect(page.raw).toBe(2);
    expect(page.items.map((a) => a.id)).toEqual(['ar1']);
    expect(page.items[0]?.imageUrl).toBeNull();
  });

  it('an unexpected library shape is an error, not a crash', () => {
    const root = JSON.parse('{"data":{}}');
    for (const parse of [parseAlbumPage, parseArtistPage]) {
      let failure: unknown = null;
      try {
        parse(root);
      } catch (e) {
        failure = e;
      }
      expect(failure).toBeInstanceOf(Error);
      expect(failure).not.toBeInstanceOf(TypeError);
      expect((failure as Error).message).toBe('Spotify library response was empty');
    }
    expect(() => parseAlbumPage(null)).toThrow('Spotify library response was empty');
    expect(() => parseArtistPage('nope')).toThrow('Spotify library response was empty');
  });
});

describe('libraryVariables', () => {
  it('sends the playlist request with only the filter changed', () => {
    const playlists = libraryVariables('Playlists', 0);
    expect(playlists).toEqual({
      filters: ['Playlists'],
      order: null,
      textFilter: '',
      features: ['LIKED_SONGS', 'YOUR_EPISODES_V2', 'PRERELEASES', 'EVENTS'],
      limit: 50,
      offset: 0,
      flatten: true,
      expandedFolders: [],
      folderUri: null,
      includeFoldersWhenFlattening: false,
    });
    const { filters: _p, ...playlistRest } = playlists;
    const { filters: _a, ...albumRest } = libraryVariables('Albums', 0);
    expect(albumRest).toEqual(playlistRest);
  });

  it('takes the filter, offset and limit it is given', () => {
    expect(libraryVariables('Albums', 100)).toMatchObject({ filters: ['Albums'], offset: 100, limit: 50 });
    expect(libraryVariables('Artists', 50, 20)).toMatchObject({ filters: ['Artists'], offset: 50, limit: 20 });
  });
});
