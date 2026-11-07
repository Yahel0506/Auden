import { beforeEach, describe, expect, it, vi } from 'vitest';
import type { MusicItem } from '@/data/music/types';

const search = vi.hoisted(() => vi.fn());
const artistId = vi.hoisted(() => vi.fn());
vi.mock('@/data/music/api', () => ({ fetchSearch: search, findArtistPageId: artistId }));

const { spotifyAlbumHref, spotifyArtistHref } = await import('./open');

const album = (id: string, title: string, subtitle = '', artists: string[] = []): MusicItem =>
  ({ kind: 'album', id, title, subtitle, artists: artists.map((name) => ({ name, id: null })), album: null, thumbnail: null, wide: false, durationSec: null, explicit: false }) as MusicItem;
const results = (albums: MusicItem[]) => ({ top: [], songs: [], artists: [], albums, playlists: [], videos: [] });

beforeEach(() => {
  search.mockReset();
  artistId.mockReset();
});

describe('opening a Spotify album on YouTube Music', () => {
  it('opens the album that answers to the title exactly, not the nearest one', async () => {
    search.mockResolvedValue(results([album('MPREb_other', 'Nevermind (Deluxe Edition)'), album('MPREb_exact', 'Nevermind')]));
    expect(await spotifyAlbumHref('Nevermind', 'Nirvana')).toBe('/album/MPREb_exact');
  });

  it('prefers the one by the same artist when two albums share a title', async () => {
    search.mockResolvedValue(results([album('MPREb_a', 'Greatest Hits', 'Album · Queen', ['Queen']), album('MPREb_b', 'Greatest Hits', 'Album · ABBA', ['ABBA'])]));
    expect(await spotifyAlbumHref('Greatest Hits', 'ABBA')).toBe('/album/MPREb_b');
  });

  it('is null when YouTube Music has none, and never opens a different album', async () => {
    search.mockResolvedValue(results([album('MPREb_x', 'Something Else')]));
    expect(await spotifyAlbumHref('Nevermind', 'Nirvana')).toBeNull();
    expect(await spotifyAlbumHref('   ', null)).toBeNull();
  });

  it('opens an artist by their exact name only', async () => {
    artistId.mockResolvedValue('UC123');
    expect(await spotifyArtistHref('Queen')).toBe('/artist/UC123');
    artistId.mockResolvedValue(null);
    expect(await spotifyArtistHref('Nobody')).toBeNull();
  });
});
