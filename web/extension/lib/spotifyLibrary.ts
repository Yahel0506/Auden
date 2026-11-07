import type { SpotifyAlbum, SpotifyArtist, SpotifyPage } from '@bitchord/shared/bridge';

/**
 * Saved albums and followed artists, read from the same `libraryV3` query the
 * playlist list uses (SpotifyLibrary.kt: libraryRoot, parseAlbumPage,
 * parseArtistPage). Pure: the caller sends the request and passes the parsed
 * JSON in, so the shapes can be tested without a browser.
 */

type Json = Record<string, unknown>;

// The helpers spotify.ts keeps private: they read a response without trusting its shape.
const obj = (v: unknown): Json | null => (v && typeof v === 'object' && !Array.isArray(v) ? (v as Json) : null);
const arr = (v: unknown): unknown[] => (Array.isArray(v) ? v : []);
const str = (v: unknown): string | null => (typeof v === 'string' ? v : null);
const num = (v: unknown): number | null => (typeof v === 'number' ? v : null);
const path = (v: unknown, ...keys: string[]): unknown => keys.reduce<unknown>((at, key) => obj(at)?.[key], v);

/**
 * The variables of the `libraryV3` query for one kind of saved item, as
 * SpotifyLibrary.libraryRoot sends them. Only the filter differs between
 * Playlists, Albums and Artists; everything else is the same request.
 */
export function libraryVariables(
  filter: 'Playlists' | 'Albums' | 'Artists',
  offset: number,
  limit = 50,
): Record<string, unknown> {
  return {
    filters: [filter],
    order: null,
    textFilter: '',
    features: ['LIKED_SONGS', 'YOUR_EPISODES_V2', 'PRERELEASES', 'EVENTS'],
    limit,
    offset,
    flatten: true,
    expandedFolders: [],
    folderUri: null,
    includeFoldersWhenFlattening: false,
  };
}

/** The albums kept, the library's total, and how many entries the page held before filtering. */
export function parseAlbumPage(data: unknown): SpotifyPage<SpotifyAlbum> {
  const library = libraryOf(data);
  const raw = arr(library.items);
  const items = raw.flatMap((entry): SpotifyAlbum[] => {
    const wrapper = obj(path(entry, 'item'));
    if (!wrapper || !str(wrapper.__typename)?.toLowerCase().includes('album')) return [];
    const item = obj(wrapper.data);
    const uri = str(wrapper._uri) ?? str(item?.uri);
    const name = str(item?.name);
    if (!item || !uri || !name?.trim()) return [];
    return [
      {
        id: uri.split(':').pop()!,
        name,
        // Every credited artist, joined the way the album row shows them; blank names are skipped.
        artist: arr(path(item, 'artists', 'items'))
          .map((a) => str(path(a, 'profile', 'name')))
          .filter((n): n is string => !!n?.trim())
          .join(', '),
        imageUrl: widest(path(item, 'coverArt', 'sources')),
      },
    ];
  });
  return { items, total: num(library.totalCount) ?? raw.length, raw: raw.length };
}

/** The artists followed, the library's total, and how many entries the page held before filtering. */
export function parseArtistPage(data: unknown): SpotifyPage<SpotifyArtist> {
  const library = libraryOf(data);
  const raw = arr(library.items);
  const items = raw.flatMap((entry): SpotifyArtist[] => {
    const wrapper = obj(path(entry, 'item'));
    if (!wrapper || !str(wrapper.__typename)?.toLowerCase().includes('artist')) return [];
    const item = obj(wrapper.data);
    const uri = str(wrapper._uri) ?? str(item?.uri);
    // The profile's name, falling back to the item's own; a blank name is dropped rather than shown.
    const name = str(path(item, 'profile', 'name')) ?? str(item?.name);
    if (!item || !uri || !name?.trim()) return [];
    return [
      {
        id: uri.split(':').pop()!,
        name,
        imageUrl: widest(path(item, 'visuals', 'avatarImage', 'sources')),
      },
    ];
  });
  return { items, total: num(library.totalCount) ?? raw.length, raw: raw.length };
}

/** The `libraryV3` object of a response, or an error: an unexpected shape is a message, not a TypeError. */
function libraryOf(data: unknown): Json {
  const library = obj(path(data, 'data', 'me', 'libraryV3'));
  if (!library) throw new Error('Spotify library response was empty');
  return library;
}

/** The widest of a list of `{url, width}` sources; the first of equal widths (SpotifyLibrary.sourcesByWidth). */
function widest(sources: unknown): string | null {
  let best: { url: string; width: number } | null = null;
  for (const source of arr(sources)) {
    const url = str(path(source, 'url'));
    const width = num(path(source, 'width')) ?? 0;
    if (url && (!best || width > best.width)) best = { url, width };
  }
  return best?.url ?? null;
}
