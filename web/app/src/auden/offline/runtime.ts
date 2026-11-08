import type { Track } from '@/data/music/types';
import { setOfflineProvider } from '@/playback/source';
import { downloadToFile, type AudioStream } from './download';
import { opfsFiles, opfsSupported, type FileStore } from './files';
import { OfflineQueue } from './queue';
import { useOfflineSettings } from './settings';
import { changeBus, dbStore, type ChangeBus, type OfflineStore } from './store';
import type { OfflineItem, OfflineSong } from './types';
import { requestPersistence } from '../db/persist';
import { onLyricsBatchDone, saveLyricsFor } from './lyrics';
import { artworkAtPixels } from '@/lib/artwork';
import { coverWidth, upgradeLowCovers } from './coverQuality';
import { hasOfflineCopy, offlineFileOf, replaceKept, setCoverPx } from './kept';

/**
 * The wiring: the real store, the OPFS folder, the player's own byte source and the queue, created once. Every tab
 * has the queue (to enqueue and to show it); a Web Lock makes one of them the runner, so two tabs never download
 * the same song, and when that tab closes another takes over.
 */

export interface OfflineRuntime {
  queue: OfflineQueue;
  store: OfflineStore;
  files: FileStore;
  bus: ChangeBus;
  /** True when this browser can keep the files at all. */
  supported: boolean;
}

const LOCK = 'auden-offline-runner';

let runtime: OfflineRuntime | null = null;
let streamOverride: AudioStream | null = null;

/** Development only: serve the bytes from somewhere other than YouTube (a stub the browser exercise uses). */
export function setOfflineStreamOverride(stream: AudioStream | null): void {
  streamOverride = stream;
}

export const songOf = (track: Track): OfflineSong => ({
  videoId: track.videoId,
  title: track.title,
  artist: track.artists.map((a) => a.name).join(', '),
  album: track.album?.name ?? null,
  albumId: track.album?.id ?? null,
  artistId: track.artists[0]?.id ?? null,
  thumbnailUrl: track.thumbnail,
  durationSec: track.durationSec,
  trackNumber: track.index,
  albumArtist: track.artists[0]?.name ?? null,
});

async function fetchCover(url: string): Promise<Blob | null> {
  try {
    // Big enough for the full-screen player (a standard size the CDN has cached), small enough to keep.
    const response = await fetch(artworkAtPixels(url, 544), { credentials: 'omit' });
    const blob = response.ok ? await response.blob() : null;
    return blob && blob.type.startsWith('image/') && blob.size <= 768 * 1024 ? blob : null;
  } catch {
    return null;
  }
}

export function getOfflineRuntime(): OfflineRuntime {
  if (runtime) return runtime;
  const supported = opfsSupported();
  const store = dbStore();
  const files = opfsFiles();
  const bus = changeBus();
  const queue = new OfflineQueue({
    store,
    files,
    bus,
    download: (song, ctx) => downloadToFile(song, files, ctx, streamOverride ?? undefined),
    fetchCover,
    onSaved: (item) => {
      if (useOfflineSettings.getState().withLyrics) saveLyricsFor(item);
      // Asked for a folder too: written now when the permission is there (state.ts), else it waits for "Allow access".
      if (item.folderPending) void import('./folder/state').then((m) => m.autoSave(item));
    },
  });
  runtime = { queue, store, files, bus, supported };
  return runtime;
}

/**
 * Registers the offline copies with the player and starts the queue's runner. Returns the cleanup. Runs for
 * everyone, switch on or off: copies already kept keep playing, and a queue that was started keeps going.
 */
export function installOffline(): () => void {
  const rt = getOfflineRuntime();
  if (!rt.supported) return () => {};
  let disposed = false;
  const refresh = async () => {
    const items = await rt.store.items().catch(() => [] as OfflineItem[]);
    if (disposed) return;
    replaceKept(items.map((i) => [i.videoId, { file: i.file, mime: i.mime, cover: i.cover }]));
    // How sharp each saved cover is decides whether the player shows it while online (kept.ts offlineCoverUrl).
    for (const i of items) if (i.cover) void coverWidth(i.cover).then((px) => !disposed && setCoverPx(i.videoId, i.cover!, px));
  };
  // Copies saved with a small cover get a sharp one once, in the background.
  const upgrade = () =>
    void upgradeLowCovers({ items: () => rt.store.items(), putItem: (i) => rt.store.putItem(i), fetchCover, width: coverWidth, onUpgraded: () => void refresh() });
  setOfflineProvider({
    has: (track) => hasOfflineCopy(track.videoId),
    read: async (track) => {
      const kept = offlineFileOf(track.videoId);
      const file = kept ? await rt.files.read(kept.file) : null;
      // OPFS hands back a file with no type; the element wants the audio's own (webm, mp4) to pick a decoder.
      return file && kept ? new Blob([file], { type: kept.mime.split(';')[0] }) : null;
    },
  });
  void refresh().then(() => {
    if (typeof navigator === 'undefined' || navigator.onLine !== false) upgrade();
  });
  onLyricsBatchDone((ids) => void import('./folder/state').then((m) => m.refreshSidecars(ids)));
  // Songs that were waiting for the folder (a closed tab, a missing permission) are tried again when the app starts.
  void import('./folder/state').then((m) => m.loadFolder().then(() => m.flushPending()));
  const off = rt.bus.subscribe(() => void refresh());
  void rt.queue.reload();

  // One runner across tabs. Without Web Locks (rare) every tab runs, which the store's keys keep harmless enough.
  const releaseLock = new AbortController();
  if (navigator.locks?.request) {
    navigator.locks
      .request(LOCK, { signal: releaseLock.signal }, () => {
        if (disposed) return undefined;
        rt.queue.setOwner(true);
        // Held until this tab's install is cleaned up, so a later install (hot reload) can take the lock.
        return new Promise<void>((release) => releaseLock.signal.addEventListener('abort', () => release(), { once: true }));
      })
      // A stolen lock (or a failed request) ends this tab's turn as the runner.
      .catch(() => {
        if (!disposed) rt.queue.setOwner(false);
      });
  } else rt.queue.setOwner(true);

  const onOnline = () => void rt.queue.onOnline();
  window.addEventListener('online', onOnline);
  return () => {
    disposed = true;
    off();
    releaseLock.abort();
    rt.queue.setOwner(false);
    window.removeEventListener('online', onOnline);
    setOfflineProvider(null);
    replaceKept([]);
    onLyricsBatchDone(null);
  };
}

/** Asks the browser to keep the copies (and the library) under storage pressure; the first download is the moment. */
export function keepStorage(): Promise<boolean> {
  return requestPersistence();
}

// Development only (stripped from a production build): lets the browser exercise swap the byte source for a stub.
if (import.meta.env.DEV) {
  (globalThis as { __audenOffline?: unknown }).__audenOffline = {
    setStream: setOfflineStreamOverride,
    runtime: getOfflineRuntime,
    /** Takes the runner lock from whichever tab holds it (a stale tab in the exercise). */
    folderApi: () => import('./folder/state'),
    folder: (handle: FileSystemDirectoryHandle, template?: string) => import('./folder/state').then((m) => m.injectFolder(handle, template)),
    steal: () => {
      const rt = getOfflineRuntime();
      void navigator.locks.request(LOCK, { steal: true }, () => {
        rt.queue.setOwner(true);
        return new Promise<void>(() => {});
      });
    },
  };
}
