import { useMutation, useQuery } from '@tanstack/react-query';
import { useEffect } from 'react';
import type { DetectedExtension, SpotifyAuthState } from '@bitchord/shared/bridge';
import { accountKeyOf, reconcileLibraryCache } from '@/data/spotify/libraryCache';
import { queryClient } from '@/lib/queryClient';
import { extensionQueryKey, useExtension } from './useExtension';

/**
 * The browser's Spotify session as the extension reports it (prompt §7.1):
 * a name and a picture, never the cookie or a token. Kept current like the
 * YouTube one: a read on start, the extension's pushes, a re-read on focus.
 */

export const spotifyAuthQueryKey = ['spotify', 'auth'] as const;

const SIGNED_OUT: SpotifyAuthState = { browserSignedIn: false, connected: true, signedIn: false, user: null, problem: null };

function detected(): DetectedExtension | null {
  return queryClient.getQueryData<DetectedExtension | null>(extensionQueryKey) ?? null;
}

const supported = (extension: DetectedExtension | null) => !!extension?.info.capabilities.includes('spotify');

/** An older extension has no album/artist methods; the library page offers those tabs only when it has. */
export const supportsSpotifyLibrary = (extension: DetectedExtension | null) => !!extension?.info.capabilities.includes('spotify.library');

/** A throttle or a dropped connection: the account is still there, only this reading failed. */
export const isTransientProblem = (state: SpotifyAuthState): boolean =>
  state.browserSignedIn && state.connected && (state.problemKind === 'network' || state.problemKind === 'rate_limited');

/** Test seam: what the extension's push and a successful read both do. */
export const adoptForTest = (state: SpotifyAuthState): void => adopt(state);

function adopt(state: SpotifyAuthState): void {
  const before = queryClient.getQueryData<SpotifyAuthState>(spotifyAuthQueryKey);
  // A reading that failed for a passing reason says nothing about the account (an older extension reports it as
  // signed out): keep the account that was read, note the problem, and leave every list where it is.
  if (before?.signedIn && isTransientProblem(state) && !state.signedIn) {
    queryClient.setQueryData(spotifyAuthQueryKey, { ...before, problem: state.problem, problemKind: state.problemKind });
    return;
  }
  queryClient.setQueryData(spotifyAuthQueryKey, state);
  reconcileLibraryCache(state);
  // Another account, or none: every list read as the old one goes.
  if (before && (before.signedIn !== state.signedIn || accountKeyOf(before) !== accountKeyOf(state))) {
    void queryClient.resetQueries({ queryKey: ['spotify', 'data'] });
  }
}

async function readAuth(refresh: boolean): Promise<SpotifyAuthState> {
  const extension = detected();
  if (!extension || !supported(extension)) return SIGNED_OUT;
  const state = await extension.bridge.call('spotify.authState', { refresh }, { timeoutMs: 30_000 });
  adopt(state);
  return state;
}

export type SpotifyAuthStatus = { state: 'checking' } | { state: 'unavailable' } | { state: 'ready'; auth: SpotifyAuthState };

export function useSpotifyAuth(): SpotifyAuthStatus {
  const extension = useExtension();
  const present = extension.state === 'present' ? extension.extension : null;
  const enabled = supported(present);
  const query = useQuery({
    queryKey: spotifyAuthQueryKey,
    queryFn: () => readAuth(false),
    enabled,
    staleTime: 60_000,
    refetchOnWindowFocus: true,
    retry: 1,
  });

  useEffect(() => {
    if (!present || !enabled) return;
    return present.bridge.subscribe((event) => {
      if (event.event === 'spotify.authState') adopt(event.data);
    });
  }, [present, enabled]);

  if (extension.state === 'checking' || (enabled && query.isPending)) return { state: 'checking' };
  if (!enabled) return { state: 'unavailable' };
  return { state: 'ready', auth: query.data ?? SIGNED_OUT };
}

/** True once Spotify requests will be made as the user. */
export function useSpotifySignedIn(): boolean {
  const status = useSpotifyAuth();
  return status.state === 'ready' && status.auth.signedIn;
}

export function useSetSpotifyConnected() {
  return useMutation({
    mutationFn: async (connected: boolean) => {
      const extension = detected();
      if (!extension) throw new Error('The extension is not installed');
      return extension.bridge.call('spotify.setConnected', { connected });
    },
    onSuccess: adopt,
  });
}

export function useRefreshSpotifyAuth() {
  return useMutation({ mutationFn: () => readAuth(true) });
}

export function openSpotifySignIn(): void {
  void detected()?.bridge.call('spotify.signIn', {});
}
