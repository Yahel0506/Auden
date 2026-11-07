// @vitest-environment happy-dom
import type { SpotifyAuthState } from '@bitchord/shared/bridge';
import { beforeEach, describe, expect, it } from 'vitest';
import { accountKeyOf, cachedList, clearLibraryCache, readLibraryCache, reconcileLibraryCache, writeLibraryList } from './libraryCache';

const state = (over: Partial<SpotifyAuthState> = {}): SpotifyAuthState => ({
  browserSignedIn: true,
  connected: true,
  signedIn: true,
  user: { name: 'Ana', avatarUrl: null, id: 'ana123' },
  problem: null,
  ...over,
});
const playlist = { id: 'p1', name: 'Mix', owner: null, imageUrl: null };

beforeEach(() => localStorage.clear());

describe('the Spotify library cache', () => {
  it('keys the account by its username, falling back to the name for an older extension', () => {
    expect(accountKeyOf(state())).toBe('ana123');
    expect(accountKeyOf(state({ user: { name: 'Ana', avatarUrl: null } }))).toBe('Ana');
    expect(accountKeyOf(state({ signedIn: false }))).toBeNull();
  });

  it('keeps each list and hands it back only to the account it belongs to', () => {
    writeLibraryList('ana123', 'playlists', [playlist]);
    writeLibraryList('ana123', 'albums', [{ id: 'a1', name: 'Album', artist: 'X', imageUrl: null }]);
    expect(cachedList('ana123', 'playlists')).toEqual([playlist]);
    expect(cachedList('ana123', 'albums')).toHaveLength(1);
    expect(cachedList('someoneElse', 'playlists')).toBeUndefined();
    expect(cachedList(null, 'playlists')).toBeUndefined();
  });

  it('keeps only one account at a time: another account\'s write replaces the first', () => {
    writeLibraryList('ana123', 'playlists', [playlist]);
    writeLibraryList('bob', 'artists', [{ id: 'r1', name: 'R', imageUrl: null }]);
    expect(readLibraryCache()).toEqual({ account: 'bob', artists: [{ id: 'r1', name: 'R', imageUrl: null }] });
  });

  it('is cleared when the browser signs out, when Spotify is disconnected, and when another account is in', () => {
    for (const change of [{ browserSignedIn: false, signedIn: false, user: null }, { connected: false, signedIn: false, user: null }, { user: { name: 'Bob', avatarUrl: null, id: 'bob' } }]) {
      writeLibraryList('ana123', 'playlists', [playlist]);
      reconcileLibraryCache(state(change));
      expect(readLibraryCache()).toBeNull();
    }
  });

  it('is NOT cleared by a passing problem: a throttle or a dropped connection keeps the lists', () => {
    writeLibraryList('ana123', 'playlists', [playlist]);
    reconcileLibraryCache(state({ problem: 'Spotify asked to slow down', problemKind: 'rate_limited' }));
    reconcileLibraryCache(state({ signedIn: false, user: null, problem: 'Couldn’t reach Spotify', problemKind: 'network' }));
    expect(cachedList('ana123', 'playlists')).toEqual([playlist]);
  });

  it('survives a store that refuses writes', () => {
    const real = Storage.prototype.setItem;
    Storage.prototype.setItem = () => {
      throw new Error('quota');
    };
    expect(() => writeLibraryList('ana123', 'playlists', [playlist])).not.toThrow();
    Storage.prototype.setItem = real;
    clearLibraryCache();
  });
});
