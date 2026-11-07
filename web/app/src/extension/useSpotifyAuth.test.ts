// @vitest-environment happy-dom
import type { SpotifyAuthState } from '@bitchord/shared/bridge';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { queryClient } from '@/lib/queryClient';

vi.mock('./useExtension', () => ({ extensionQueryKey: ['extension'], useExtension: () => ({ state: 'checking' }) }));

const { isTransientProblem, spotifyAuthQueryKey, useSetSpotifyConnected } = await import('./useSpotifyAuth');
void useSetSpotifyConnected;

const ana = { name: 'Ana', avatarUrl: null, id: 'ana123' };
const state = (over: Partial<SpotifyAuthState> = {}): SpotifyAuthState => ({ browserSignedIn: true, connected: true, signedIn: true, user: ana, problem: null, ...over });

describe('isTransientProblem', () => {
  it('is a throttle or a dropped connection while still connected, and nothing else', () => {
    expect(isTransientProblem(state({ signedIn: false, user: null, problemKind: 'network' }))).toBe(true);
    expect(isTransientProblem(state({ signedIn: false, user: null, problemKind: 'rate_limited' }))).toBe(true);
    expect(isTransientProblem(state({ signedIn: false, user: null, problemKind: 'expired' }))).toBe(false);
    expect(isTransientProblem(state({ problemKind: undefined }))).toBe(false);
    expect(isTransientProblem(state({ connected: false, problemKind: 'network' }))).toBe(false);
    expect(isTransientProblem(state({ browserSignedIn: false, problemKind: 'network' }))).toBe(false);
  });
});

describe('adopting an auth state', () => {
  // adopt() is reached through the extension's push; the same path is exercised through the mutation hook's onSuccess.
  beforeEach(() => queryClient.clear());

  it('keeps the account and resets nothing when a reading fails for a passing reason', async () => {
    const { adoptForTest } = await import('./useSpotifyAuth');
    queryClient.setQueryData(spotifyAuthQueryKey, state());
    const reset = vi.spyOn(queryClient, 'resetQueries');
    adoptForTest(state({ signedIn: false, user: null, problem: 'Couldn’t reach Spotify', problemKind: 'network' }));
    expect(queryClient.getQueryData<SpotifyAuthState>(spotifyAuthQueryKey)).toMatchObject({ signedIn: true, user: ana, problemKind: 'network' });
    expect(reset).not.toHaveBeenCalled();
    reset.mockRestore();
  });

  it('does reset when the account really changed or went', async () => {
    const { adoptForTest } = await import('./useSpotifyAuth');
    queryClient.setQueryData(spotifyAuthQueryKey, state());
    const reset = vi.spyOn(queryClient, 'resetQueries').mockResolvedValue();
    adoptForTest(state({ signedIn: false, user: null, problem: 'expired', problemKind: 'expired' }));
    expect(reset).toHaveBeenCalledWith({ queryKey: ['spotify', 'data'] });
    reset.mockRestore();
  });

  it('does reset for another account', async () => {
    const { adoptForTest } = await import('./useSpotifyAuth');
    queryClient.setQueryData(spotifyAuthQueryKey, state());
    const reset = vi.spyOn(queryClient, 'resetQueries').mockResolvedValue();
    adoptForTest(state({ user: { name: 'Bob', avatarUrl: null, id: 'bob' } }));
    expect(reset).toHaveBeenCalled();
    reset.mockRestore();
  });
});
