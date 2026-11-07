import type { LibraryRootRow, LocalArtRow, TrackRow } from './schema';
import { openAudenDb } from './schema';
import { notifyStores } from './notify';

/** `auden-file://<rootId>/<relPath>`: the stable key of a web file (rooted so two folders can hold the same path). */
export function localUriOf(rootId: string, relPath: string): string {
  return `auden-file://${rootId}/${relPath.split('/').map(encodeURIComponent).join('/')}`;
}

export function parseLocalUri(uri: string): { rootId: string; relPath: string } | null {
  const m = /^auden-file:\/\/([^/]+)\/(.+)$/.exec(uri);
  if (!m) return null;
  try {
    return { rootId: m[1]!, relPath: m[2]!.split('/').map(decodeURIComponent).join('/') };
  } catch {
    return null;
  }
}

export const rootUriPrefix = (rootId: string): string => `auden-file://${rootId}/`;

export const libraryRoots = {
  async all(): Promise<LibraryRootRow[]> {
    return (await openAudenDb()).getAll('library_root');
  },
  async get(id: string): Promise<LibraryRootRow | undefined> {
    return (await openAudenDb()).get('library_root', id);
  },
  async put(row: LibraryRootRow): Promise<void> {
    await (await openAudenDb()).put('library_root', row);
    notifyStores('library_root');
  },
  /**
   * Removes a folder and its index. A row with a YouTube id keeps existing, detached from the file
   * (Android's rule: the streaming copy is still a track, and playlists/stats point at it); the rest go,
   * with the playlist entries and plays that referenced them.
   */
  async remove(id: string): Promise<void> {
    const db = await openAudenDb();
    const prefix = rootUriPrefix(id);
    const tx = db.transaction(['library_root', 'track', 'playlist_entry', 'play_event'], 'readwrite');
    const range = IDBKeyRange.bound(prefix, prefix + '￿');
    let cursor = await tx.objectStore('track').index('localUri').openCursor(range);
    while (cursor) {
      const row = cursor.value;
      if (row.videoId) {
        await cursor.update({ ...row, localUri: null, path: null, folder: null, sizeBytes: null, modifiedAt: null, origin: 'YTM' } as TrackRow);
      } else {
        const trackId = row.id!;
        for (const store of ['playlist_entry', 'play_event'] as const) {
          let c = await tx.objectStore(store).index('trackId').openCursor(trackId);
          while (c) {
            await c.delete();
            c = await c.continue();
          }
        }
        await cursor.delete();
      }
      cursor = await cursor.continue();
    }
    await tx.objectStore('library_root').delete(id);
    await tx.done;
    notifyStores('library_root', 'track', 'playlist_entry', 'play_event');
  },
};

export const localArt = {
  async get(key: string): Promise<LocalArtRow | undefined> {
    return (await openAudenDb()).get('local_art', key);
  },
  async all(): Promise<LocalArtRow[]> {
    return (await openAudenDb()).getAll('local_art');
  },
  async put(row: LocalArtRow): Promise<void> {
    await (await openAudenDb()).put('local_art', row);
    notifyStores('local_art');
  },
};
