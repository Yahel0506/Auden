import type { SpotifyAlbum, SpotifyArtist, SpotifyAuthState, SpotifyPlaylist } from '@bitchord/shared/bridge';

/**
 * The library lists, kept so the page opens at once and offline (SpotifyLibraryCache.kt, which used
 * SharedPreferences). One account's lists at a time, in localStorage (a few hundred KB at most), and
 * cleared when the account goes: the phone never clears it, so the next account would see the last one's.
 * Only a CONFIRMED change clears it. A throttle or a dropped connection is not one.
 */
const KEY = 'auden:spotify:library';

export interface LibraryCache {
  /** Who the lists belong to (`user.id`, else the name for an older extension). */
  account: string;
  playlists?: SpotifyPlaylist[];
  albums?: SpotifyAlbum[];
  artists?: SpotifyArtist[];
}

export type LibraryKind = 'playlists' | 'albums' | 'artists';

export const accountKeyOf = (state: SpotifyAuthState): string | null => (state.signedIn && state.user ? (state.user.id ?? state.user.name) : null);

export function readLibraryCache(): LibraryCache | null {
  try {
    const parsed = JSON.parse(localStorage.getItem(KEY) ?? 'null') as LibraryCache | null;
    return parsed && typeof parsed.account === 'string' ? parsed : null;
  } catch {
    return null;
  }
}

/** The cached list of [kind], only if it is [account]'s. */
export function cachedList<K extends LibraryKind>(account: string | null, kind: K): NonNullable<LibraryCache[K]> | undefined {
  const cache = readLibraryCache();
  return account && cache?.account === account ? (cache[kind] as NonNullable<LibraryCache[K]> | undefined) : undefined;
}

export function writeLibraryList<K extends LibraryKind>(account: string, kind: K, list: NonNullable<LibraryCache[K]>): void {
  try {
    const cache = readLibraryCache();
    const next: LibraryCache = cache?.account === account ? { ...cache } : { account };
    next[kind] = list as never;
    localStorage.setItem(KEY, JSON.stringify(next));
  } catch {
    // A full or unavailable store only costs the offline copy.
  }
}

export function clearLibraryCache(): void {
  try {
    localStorage.removeItem(KEY);
  } catch {
    // Nothing to clear.
  }
}

/**
 * Clears the cache when [state] says, for sure, that it no longer belongs: the browser signed out,
 * the listener disconnected Spotify, or another account is in. A problem leaves it alone.
 */
export function reconcileLibraryCache(state: SpotifyAuthState): void {
  const cache = readLibraryCache();
  if (!cache) return;
  const account = accountKeyOf(state);
  if (!state.browserSignedIn || !state.connected || (account !== null && account !== cache.account)) clearLibraryCache();
}
