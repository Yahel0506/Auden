// @vitest-environment happy-dom
import { QueryClientProvider } from '@tanstack/react-query';
import { act, createElement } from 'react';
import { createRoot, type Root } from 'react-dom/client';
import { MemoryRouter } from 'react-router';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { queryClient } from '@/lib/queryClient';
import { writeLibraryList } from '@/data/spotify/libraryCache';

(globalThis as { IS_REACT_ACT_ENVIRONMENT?: boolean }).IS_REACT_ACT_ENVIRONMENT = true;

const auth = vi.hoisted(() => ({ value: null as unknown }));
vi.mock('@/extension/useExtension', () => ({
  extensionQueryKey: ['extension'],
  useExtension: () => ({ state: 'present', extension: { info: { capabilities: ['spotify', 'spotify.library'] }, bridge: {} } }),
}));
vi.mock('@/extension/useSpotifyAuth', async (original) => {
  const real = await original<typeof import('@/extension/useSpotifyAuth')>();
  return {
    ...real,
    useSpotifyAuth: () => ({ state: 'ready', auth: auth.value }),
    useSpotifySignedIn: () => (auth.value as { signedIn: boolean }).signedIn,
    useRefreshSpotifyAuth: () => ({ mutate: () => {}, isPending: false }),
    useSetSpotifyConnected: () => ({ mutate: () => {}, isPending: false }),
  };
});

const { default: SpotifyPage } = await import('./SpotifyPage');

let root: Root;
let host: HTMLElement;
beforeEach(async () => {
  localStorage.clear();
  queryClient.clear();
  host = document.createElement('div');
  document.body.appendChild(host);
  root = createRoot(host);
});
afterEach(async () => {
  await act(async () => root.unmount());
  host.remove();
});

const render = () => act(async () => root.render(createElement(QueryClientProvider, { client: queryClient }, createElement(MemoryRouter, null, createElement(SpotifyPage)))));
const signedOut = (problemKind: string) => ({ browserSignedIn: true, connected: true, signedIn: false, user: null, problem: 'x', problemKind });

describe('SpotifyPage on a cold start with a passing problem', () => {
  it('shows the saved lists, and says why, instead of "Couldn\'t read your account"', async () => {
    writeLibraryList('ana123', 'playlists', [{ id: 'p1', name: 'My Saved Mix', owner: 'Ana', imageUrl: null }]);
    auth.value = signedOut('network');
    await render();
    expect(host.textContent).toContain('My Saved Mix');
    expect(host.textContent).toContain('Couldn’t reach Spotify. Check your connection. Showing what was saved.');
    expect(host.textContent).not.toContain('Couldn’t read your Spotify account');
  });

  it('with nothing saved there is nothing to show: the account screen stays', async () => {
    auth.value = signedOut('rate_limited');
    await render();
    expect(host.textContent).toContain('Couldn’t read your Spotify account');
  });

  it('a real sign-out is not softened by the cache: the saved lists are not shown for it', async () => {
    writeLibraryList('ana123', 'playlists', [{ id: 'p1', name: 'My Saved Mix', owner: 'Ana', imageUrl: null }]);
    auth.value = signedOut('expired');
    await render();
    expect(host.textContent).not.toContain('My Saved Mix');
  });
});
