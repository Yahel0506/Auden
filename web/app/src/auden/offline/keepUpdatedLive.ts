import { fetchPlaylist } from '@/data/music/api';
import type { Track } from '@/data/music/types';
import { getPlaylist, playlistTracks } from '../playlists/store';
import { canKeepOffline } from './actions';
import { PAGE_CAP, syncAll, syncOne, type PassReport, type Reading, type SyncDeps } from './keepUpdated';
import { getOfflineRuntime, keepStorage, songOf } from './runtime';
import type { Followed } from './types';
import { followedKey } from './types';

/** Reads a playlist's songs: a device playlist from its store; a YouTube Music one page by page, stopping when a page adds nothing new. */
export async function readFollowed(f: Followed): Promise<Reading> {
  if (f.kind === 'local') {
    if (!(await getPlaylist(f.id))) return 'gone';
    return { tracks: await playlistTracks(f.id), complete: true };
  }
  try {
    const { page, more } = await fetchPlaylist(f.id);
    const seen = new Set(page.tracks.map((t) => t.videoId));
    const tracks: Track[] = [...page.tracks];
    let next = more;
    for (let pages = 1; next && pages < PAGE_CAP; pages++) {
      const chunk = await next();
      const fresh = chunk.data.filter((t) => !seen.has(t.videoId));
      // A page with nothing new means the feed has looped.
      if (!fresh.length) break;
      for (const t of fresh) seen.add(t.videoId);
      tracks.push(...fresh);
      next = chunk.next;
    }
    return { tracks, complete: !next };
  } catch {
    return null;
  }
}

const withLock = <T>(fn: () => Promise<T>): Promise<T> =>
  typeof navigator !== 'undefined' && navigator.locks ? navigator.locks.request('auden-offline-followed', fn) : fn();

export function liveDeps(): SyncDeps {
  const rt = getOfflineRuntime();
  return {
    store: rt.store,
    read: readFollowed,
    enqueue: async (tracks, toFolder) => {
      const songs = tracks.filter(canKeepOffline).map(songOf);
      if (!songs.length) return;
      void keepStorage();
      // The wish for the folder rides on the job: the file is written when the download ends (state.ts autoSave), no click needed.
      await rt.queue.enqueue(songs, { toFolder });
    },
    locked: withLock,
    now: Date.now,
    changed: () => rt.bus.emit(),
  };
}

export const syncAllLive = (gate: Parameters<typeof syncAll>[1]): Promise<PassReport> => syncAll(liveDeps(), gate);

/** "Download" on a question: that playlist, now, with the yes. */
export async function approveLive(key: string): Promise<PassReport> {
  const deps = liveDeps();
  const f = (await deps.store.followed())[key];
  return f ? syncOne(deps, key, f, true) : { queued: 0, asking: 0, gone: 0, failed: 0 };
}

export { followedKey };
