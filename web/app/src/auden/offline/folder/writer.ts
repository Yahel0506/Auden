import type { FileStore } from '../files';
import type { OfflineStore } from '../store';
import type { OfflineItem } from '../types';
import { type DirHandleLike } from './dirs';
import { extensionOf, folderOf, pathKey, relativeName, stemOf, withSuffix } from './layout';

/**
 * Writes kept songs into the folder the listener chose: the audio exactly as YouTube served it (no tags: there is
 * no tag writer for Opus/WebM/AAC here), a `.lrc` beside it when synced lyrics are saved, and one cover per album.
 *
 * Nothing the listener already has is ever overwritten: a path that is taken, by anything but this song's own
 * earlier save, gets " (2)", " (3)"… The audio is written through one writable and verified by size, so a failed
 * write leaves no partial file. Whole batches run under one Web Lock, so two tabs can't probe-then-create the same path.
 */

export type SaveStatus = 'saved' | 'already' | 'failed';

export interface SaveResult {
  videoId: string;
  status: SaveStatus;
  path?: string;
  reason?: string;
  /** A `.lrc` is beside it. */
  lrc: boolean;
}

/** Why a batch stopped early (the rest stay pending, not failed): permission taken back, the folder gone, no room. */
export type StopReason = 'permission' | 'folder-missing' | 'disk-full';

export interface SaveReport {
  results: SaveResult[];
  stopped: StopReason | null;
}

export interface WriterDeps {
  root: DirHandleLike;
  template: string;
  files: Pick<FileStore, 'read'>;
  /** `item` re-reads a song inside the lock: the data a batch was asked with may be stale by then (another batch, a removal). */
  store: Pick<OfflineStore, 'putItem' | 'item'>;
  /** The `.lrc` text for a song, or null when it has no synced lyrics (yet). */
  lyrics?: (item: OfflineItem) => Promise<string | null>;
  now?: () => number;
  /** Runs [fn] exclusively (a Web Lock); absent = no exclusion. */
  lock?: <T>(fn: () => Promise<T>) => Promise<T>;
}

const FOLDER_LOCK = 'auden-offline-folder';

export function withFolderLock<T>(fn: () => Promise<T>): Promise<T> {
  return navigator.locks?.request ? navigator.locks.request(FOLDER_LOCK, fn) : fn();
}

const nameOf = (error: unknown): string => (error as { name?: string } | null)?.name ?? '';
const messageOf = (error: unknown): string => (error instanceof Error ? error.message : String(error)) || nameOf(error);

function stopFor(error: unknown): StopReason | null {
  const name = nameOf(error);
  if (name === 'NotAllowedError' || name === 'SecurityError') return 'permission';
  if (name === 'QuotaExceededError') return 'disk-full';
  return null;
}

/** `null` when nothing is at [path]; the file's size when there is something (a folder in the way counts as taken). */
async function sizeAt(root: DirHandleLike, path: string): Promise<number | null> {
  const parts = path.split('/');
  try {
    let dir = root;
    for (const part of parts.slice(0, -1)) dir = await dir.getDirectoryHandle(part);
    return (await (await dir.getFileHandle(parts[parts.length - 1]!)).getFile()).size;
  } catch (error) {
    const name = nameOf(error);
    if (name === 'NotFoundError') return null;
    if (name === 'TypeMismatchError') return Number.POSITIVE_INFINITY; // a folder where a file would go: taken
    throw error;
  }
}

async function dirFor(root: DirHandleLike, folder: string): Promise<DirHandleLike> {
  let dir = root;
  for (const part of folder.split('/').filter(Boolean)) dir = await dir.getDirectoryHandle(part, { create: true });
  return dir;
}

async function writeBlob(dir: DirHandleLike, name: string, blob: Blob): Promise<void> {
  // The path was checked free just before, so whatever exists after a failure is this write's: it goes.
  let created = false;
  let writable: Awaited<ReturnType<Awaited<ReturnType<DirHandleLike['getFileHandle']>>['createWritable']>> | null = null;
  try {
    const handle = await dir.getFileHandle(name, { create: true });
    created = true;
    writable = await handle.createWritable();
    if (typeof blob.stream === 'function') {
      const reader = blob.stream().getReader();
      for (;;) {
        const { done, value } = await reader.read();
        if (done) break;
        await writable.write(value);
      }
    } else {
      await writable.write(new Uint8Array(await blob.arrayBuffer()));
    }
    await writable.close();
    const size = (await handle.getFile()).size;
    if (size !== blob.size) throw new Error(`the folder kept ${size} of ${blob.size} bytes`);
  } catch (error) {
    await writable?.abort().catch(() => undefined);
    if (created) await dir.removeEntry(name).catch(() => undefined);
    throw error;
  }
}

const COVER_EXT: Record<string, string> = { 'image/jpeg': 'jpg', 'image/png': 'png', 'image/webp': 'webp' };

/** Saves [items] into [deps.root], one after another. Never throws for one song's trouble; a stop reason ends the batch. */
export async function saveToFolder(items: readonly OfflineItem[], deps: WriterDeps): Promise<SaveReport> {
  const run = deps.lock ?? ((fn) => fn());
  return run(() => saveLocked(items, deps));
}

async function saveLocked(items: readonly OfflineItem[], deps: WriterDeps): Promise<SaveReport> {
  const { root, template, files, store, lyrics } = deps;
  const now = deps.now ?? Date.now;
  const results: SaveResult[] = [];
  const coverDone = new Set<string>();
  let stopped: StopReason | null = null;

  const sidecar = async (item: OfflineItem, path: string): Promise<boolean> => {
    const text = await lyrics?.(item).catch(() => null);
    if (!text) return false;
    const lrcPath = `${folderOf(path) ? `${folderOf(path)}/` : ''}${stemOf(path)}.lrc`;
    // A .lrc already there is the listener's (or an earlier save's): never replaced.
    if ((await sizeAt(root, lrcPath)) !== null) return true;
    await writeBlob(await dirFor(root, folderOf(lrcPath)), lrcPath.slice(lrcPath.lastIndexOf('/') + 1), new Blob([text], { type: 'text/plain' }));
    return true;
  };

  for (const asked of items) {
    if (stopped) break;
    // Inside the lock the stored song is the truth: it may have been saved by another batch, or removed, since this one began.
    const item = await store.item(asked.videoId).catch(() => asked);
    if (!item) continue;
    try {
      // A song saved before whose file is still at its path: nothing to write (even if the listener edited it, say tagged it, and
      // so changed its size), but a .lrc that was missing may exist now. Only an empty path means it must be written again.
      if (item.folder) {
        const have = await sizeAt(root, item.folder.path);
        if (have !== null && Number.isFinite(have)) {
          let lrc = item.folder.lrc;
          if (!lrc) lrc = await sidecar(item, item.folder.path).catch((error: unknown) => {
            if (stopFor(error)) stopped = stopFor(error);
            return false;
          });
          if (lrc !== item.folder.lrc || item.folderPending) {
            const { folderPending: _drop, ...rest } = item;
            await store.putItem({ ...rest, folder: { ...item.folder, lrc } });
          }
          results.push({ videoId: item.videoId, status: 'already', path: item.folder.path, lrc });
          continue;
        }
      }
      const blob = await files.read(item.file);
      if (!blob) {
        results.push({ videoId: item.videoId, status: 'failed', reason: 'The offline copy is no longer kept.', lrc: false });
        continue;
      }
      const wanted = relativeName(
        template,
        { title: item.title, artist: item.artist, albumArtist: item.albumArtist, album: item.album, track: item.trackNumber ?? null, videoId: item.videoId },
        extensionOf(item.file),
      );
      let path = '';
      for (let n = 1; n <= 99 && !path; n++) {
        const candidate = n === 1 ? wanted : withSuffix(wanted, n);
        if ((await sizeAt(root, candidate)) === null) path = candidate;
      }
      if (!path) {
        results.push({ videoId: item.videoId, status: 'failed', reason: 'Too many files with this name in the folder.', lrc: false });
        continue;
      }
      const folder = folderOf(path);
      const dir = await dirFor(root, folder);
      await writeBlob(dir, path.slice(path.lastIndexOf('/') + 1), blob);

      // The audio is in the folder: record it NOW, before the optional files, so a stop in them can't leave a file the
      // next run would not recognise as this song's (and write again as " (2)").
      const { folderPending: _pending, ...rest } = item;
      const saved = { ...rest, folder: { path, savedAt: now(), bytes: blob.size, lrc: false } };
      await store.putItem(saved);
      const result: SaveResult = { videoId: item.videoId, status: 'saved', path, lrc: false };
      results.push(result);

      try {
        result.lrc = await sidecar(item, path);
        if (result.lrc) await store.putItem({ ...saved, folder: { ...saved.folder, lrc: true } });
      } catch (error) {
        if (stopFor(error)) stopped = stopFor(error);
      }
      // One cover per real album folder: never a single's art for the whole "Singles" folder or the folder root.
      if (!stopped && item.cover && item.album && folder && !coverDone.has(pathKey(folder))) {
        coverDone.add(pathKey(folder));
        const ext = COVER_EXT[item.cover.type] ?? 'jpg';
        try {
          if ((await sizeAt(root, `${folder}/cover.${ext}`)) === null) await writeBlob(dir, `cover.${ext}`, item.cover);
        } catch (error) {
          if (stopFor(error)) stopped = stopFor(error);
        }
      }
    } catch (error) {
      const stop = stopFor(error);
      if (stop) {
        stopped = stop;
        break;
      }
      // The chosen folder itself is gone (moved, deleted, unplugged): every item would hit the same wall.
      if (nameOf(error) === 'NotFoundError') {
        stopped = 'folder-missing';
        break;
      }
      results.push({ videoId: item.videoId, status: 'failed', reason: messageOf(error), lrc: false });
    }
  }
  return { results, stopped };
}

