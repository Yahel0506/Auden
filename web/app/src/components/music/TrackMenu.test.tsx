// @vitest-environment happy-dom
import { act, createElement } from 'react';
import { createRoot, type Root } from 'react-dom/client';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import type { Track } from '@/data/music/types';

(globalThis as { IS_REACT_ACT_ENVIRONMENT?: boolean }).IS_REACT_ACT_ENVIRONMENT = true;

vi.mock('@/data/music/queries', () => ({
  useSignedIn: () => true,
  useLikeStatus: () => ({ data: null, isPending: false }),
  useSetLiked: () => ({ mutate: () => {} }),
  useAddToPlaylist: () => ({ mutate: () => {} }),
  usePlaylistOptions: () => ({ isPending: false, isError: false, data: [{ playlistId: 'PL1', title: 'My Mix' }] }),
}));
vi.mock('@/party/listenTogether', async () => {
  const { useRef } = await import('react');
  // A real hook, as the store's is: what matters here is how many hooks a render runs.
  return { useParty: (select: (s: { code: string | null }) => unknown) => (useRef(null), select({ code: null })) };
});
vi.mock('@/state/player', () => ({ usePlayer: { getState: () => ({ playNext: () => {}, addToQueue: () => {} }) } }));
const offline = vi.hoisted(() => ({ available: false, status: 'none' as 'none' | 'queued' | 'kept', save: vi.fn(), remove: vi.fn() }));
vi.mock('@/auden/offline/hooks', () => ({ useOfflineAvailable: () => offline.available, useOfflineStatus: () => offline.status }));
vi.mock('@/auden/offline/actions', () => ({ canKeepOffline: () => true, saveForOffline: offline.save, removeOfflineCopies: offline.remove }));
const folder = vi.hoisted(() => ({ offer: false, save: vi.fn(async () => ({ kind: 'no-folder' })) }));
vi.mock('@/auden/offline/folder/hooks', () => ({ useFolderOffer: () => folder.offer }));
vi.mock('@/auden/offline/folder/state', () => ({ saveSongsToFolder: folder.save }));
vi.mock('@/auden/offline/folder/report', () => ({ announce: vi.fn() }));
vi.mock('@/auden/lyrics/batch', () => ({ startLyricsBatch: vi.fn() }));
vi.mock('@/auden/lyrics/key', () => ({ lyricsKeyFor: async (videoId: string) => `key:${videoId}` }));

const { TrackMenu } = await import('./TrackMenu');
const { useSavedLyricsDialog } = await import('@/auden/lyrics/dialog');

const track: Track = {
  videoId: 'abcdefghijk',
  title: 'Song',
  artists: [{ name: 'Artist', id: null }],
  album: null,
  durationSec: 200,
  explicit: false,
  thumbnail: null,
  index: null,
  plays: null,
};

let root: Root;
let host: HTMLElement;
async function open() {
  await act(async () => root.render(createElement(TrackMenu, { track })));
  await act(async () => host.querySelector<HTMLButtonElement>('button[aria-haspopup=menu]')!.click());
}
beforeEach(async () => {
  offline.available = false;
  offline.status = 'none';
  folder.offer = false;
  useSavedLyricsDialog.getState().hide();
  host = document.createElement('div');
  document.body.appendChild(host);
  root = createRoot(host);
  await open();
});
afterEach(async () => {
  await act(async () => root.unmount());
  host.remove();
  document.body.innerHTML = '';
});

const item = (text: string) => [...document.querySelectorAll<HTMLElement>('[role=menuitem], [role=menu] *')].find((e) => e.textContent?.trim() === text);

describe('TrackMenu', () => {
  it('switches to the playlist choices and back without changing how many hooks run', async () => {
    expect(item('Add to Playlist')).toBeTruthy();
    // Before the fix a hook after the early return threw "Rendered fewer hooks than expected" here.
    await act(async () => item('Add to Playlist')!.click());
    expect(item('My Mix')).toBeTruthy();
    await act(async () => item('Back')!.click());
    expect(item('Play Next')).toBeTruthy();
  });

  it('opens the saved lyrics from app level, so closing the menu does not take the dialog with it', async () => {
    await act(async () => item('Saved Lyrics…')!.click());
    await act(async () => { await new Promise((r) => setTimeout(r, 10)); });
    expect(useSavedLyricsDialog.getState().open).toMatchObject({ songKey: 'key:abcdefghijk', track: { videoId: 'abcdefghijk', title: 'Song', artist: 'Artist', durationMs: 200_000 } });
    // The menu is gone; the request lives in the store, not in it.
    expect(document.querySelector('[role=menu]')).toBeNull();
  });

  it('offers Save for Offline only when the feature is available, and Remove Offline Copy for a kept song', async () => {
    expect(item('Save for Offline')).toBeUndefined();
    await act(async () => root.unmount());
    root = createRoot(host);
    offline.available = true;
    await open();
    await act(async () => item('Save for Offline')!.click());
    expect(offline.save).toHaveBeenCalledWith([track]);

    await act(async () => root.unmount());
    root = createRoot(host);
    offline.status = 'kept';
    offline.available = false; // a kept copy can be removed even with the feature switched off
    await open();
    await act(async () => item('Remove Offline Copy')!.click());
    expect(offline.remove).toHaveBeenCalledWith(['abcdefghijk']);
  });

  it('offers Save to Folder only when the folder is usable, for a kept song or one that can be downloaded', async () => {
    expect(item('Save to Folder')).toBeUndefined();
    await act(async () => root.unmount());
    root = createRoot(host);
    folder.offer = true;
    offline.available = true;
    await open();
    await act(async () => item('Save to Folder')!.click());
    expect(folder.save).toHaveBeenCalledWith([track]);

    await act(async () => root.unmount());
    root = createRoot(host);
    offline.status = 'kept';
    offline.available = false;
    await open();
    expect(item('Save to Folder')).toBeTruthy();
    expect(item('Remove Offline Copy')).toBeTruthy();
  });
});
