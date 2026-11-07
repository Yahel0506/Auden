import type { Track } from '@/data/music/types';
import { openAudenDb } from '../db/schema';
import { libraryRoots } from '../db/libraryDao';
import { subscribeToStores } from '../db/notify';
import { setLocalResolver } from '@/playback/source';
import { clearLinkCache, hasLink, linksOf, rebuildLinkIndex, type LinkEntry } from './linkIndex';
import { isUsable, onRootsChanged, refreshRoots, sessionFiles } from './roots';

/** The file behind one index entry. Query-only: there is no gesture here, so a root that needs a tap is "not there". */
async function fileOf(entry: LinkEntry): Promise<File | null> {
  if (!isUsable(entry.rootId)) return null;
  const picked = sessionFiles.get(entry.rootId);
  if (picked) {
    const file = picked.get(entry.relPath) ?? null;
    // A different file under the same name is not this song.
    return file && (entry.row.sizeBytes == null || entry.row.sizeBytes === file.size) ? file : null;
  }
  const root = await libraryRoots.get(entry.rootId);
  if (!root?.handle) return null;
  const parts = entry.relPath.split('/');
  let dir: FileSystemDirectoryHandle = root.handle;
  for (const part of parts.slice(0, -1)) dir = await dir.getDirectoryHandle(part);
  const file = await (await dir.getFileHandle(parts[parts.length - 1]!)).getFile();
  // The file changed since it was indexed: it may no longer be the recording that was linked.
  if (entry.row.sizeBytes != null && entry.row.sizeBytes !== file.size) return null;
  return file;
}

/** The first readable file for [track]; errors from a file that exists but can't be read propagate (the player falls back to YouTube). */
export async function resolveFile(track: Track): Promise<Blob | null> {
  let failure: unknown = null;
  for (const entry of linksOf(track)) {
    try {
      const file = await fileOf(entry);
      if (file) return file;
    } catch (error) {
      failure = error;
    }
  }
  if (failure) throw failure;
  return null;
}

/**
 * Registers the library with the player: the resolver and its synchronous probe, and keeps the in-memory index
 * current (rows changed here or in another tab, folders reconnected, the tab coming back to the front).
 */
export function installLocalLibrary(): () => void {
  let disposed = false;
  // One rebuild at a time, with one trailing re-run: a scan or identification writes in bursts, and
  // overlapping rebuilds could finish out of order and leave an older index in place.
  let running = false;
  let again = false;
  let timer: ReturnType<typeof setTimeout> | undefined;
  const rebuild = async (): Promise<void> => {
    if (running) {
      again = true;
      return;
    }
    running = true;
    try {
      do {
        again = false;
        // Only rows that have a file are in the localUri index.
        const rows = await (await openAudenDb()).getAllFromIndex('track', 'localUri');
        if (!disposed) rebuildLinkIndex(rows);
      } while (again && !disposed);
    } finally {
      running = false;
    }
  };
  const soon = () => {
    clearTimeout(timer);
    timer = setTimeout(() => void rebuild(), 300);
  };
  void refreshRoots().then(rebuild);
  setLocalResolver(resolveFile, hasLink);
  const offStores = subscribeToStores((stores) => {
    if (stores.includes('track')) soon();
    if (stores.includes('library_root')) void refreshRoots();
  });
  const offRoots = onRootsChanged(clearLinkCache);
  const onVisible = () => {
    if (document.visibilityState === 'visible') void refreshRoots();
  };
  document.addEventListener('visibilitychange', onVisible);
  window.addEventListener('focus', onVisible);
  return () => {
    disposed = true;
    clearTimeout(timer);
    setLocalResolver(null);
    offStores();
    offRoots();
    document.removeEventListener('visibilitychange', onVisible);
    window.removeEventListener('focus', onVisible);
  };
}
