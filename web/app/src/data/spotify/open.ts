import { albumHref, artistHref } from '@/components/music/links';
import { fetchSearch, findArtistPageId } from '@/data/music/api';
import { isExactArtistMatch, normalizedArtistName } from '@/lib/artistCredits';

/**
 * Where a Spotify album or artist opens: its YouTube Music page, found by search (MainViewModel.openSpotifyAlbum).
 * A name is not an identity, so only a result that answers to it exactly is opened, never the nearest one;
 * null means YouTube Music has no such page.
 */
export async function spotifyAlbumHref(name: string, artist: string | null): Promise<string | null> {
  if (!normalizedArtistName(name)) return null;
  const results = await fetchSearch([name, artist].filter(Boolean).join(' '));
  const sameTitle = results.albums.filter((a) => a.kind === 'album' && normalizedArtistName(a.title) === normalizedArtistName(name));
  // Two albums can share a title: the one by this artist, when the subtitle names them.
  const pick = (artist && sameTitle.find((a) => a.artists.some((x) => isExactArtistMatch(x.name, artist)) || a.subtitle.includes(artist))) || sameTitle[0];
  return albumHref(pick?.id ?? null);
}

export async function spotifyArtistHref(name: string): Promise<string | null> {
  return artistHref(await findArtistPageId(name));
}
