import type { LibraryRootRow } from '../../db/schema';
import type { FolderRecord } from './record';

/**
 * The chosen folder and the permission on it: the pattern of the library folders (auden/library/roots.ts), with
 * `readwrite`. The handle is structured-cloned into IndexedDB; permission is only ever *queried* without a gesture
 * and *requested* straight from a click, as the first thing the click does.
 */
export type FolderAccess = 'granted' | 'prompt' | 'denied' | 'missing';

interface PermissionHandle extends FileSystemDirectoryHandle {
  queryPermission(d: { mode: 'readwrite' }): Promise<PermissionState>;
  requestPermission(d: { mode: 'readwrite' }): Promise<PermissionState>;
}

/** Chromium's picker; Firefox and Safari don't have it, and the feature is hidden there. */
export function folderSupported(): boolean {
  return typeof window !== 'undefined' && typeof (window as unknown as { showDirectoryPicker?: unknown }).showDirectoryPicker === 'function';
}

/** Without asking: what the browser would say now. A folder that was moved or deleted is "missing". */
export async function folderAccess(handle: FileSystemDirectoryHandle): Promise<FolderAccess> {
  try {
    const state = await (handle as PermissionHandle).queryPermission({ mode: 'readwrite' });
    if (state === 'granted') {
      // Permission can outlive the folder: touch it.
      await handle.entries().next();
      return 'granted';
    }
    return state === 'denied' ? 'denied' : 'prompt';
  } catch (error) {
    return (error as { name?: string }).name === 'NotFoundError' ? 'missing' : 'prompt';
  }
}

/** The "Allow access" tap. Call it from the click handler with the handle already in hand: it must be the first await. */
export async function allowFolder(handle: FileSystemDirectoryHandle): Promise<boolean> {
  try {
    return (await (handle as PermissionHandle).requestPermission({ mode: 'readwrite' })) === 'granted';
  } catch {
    return false;
  }
}

/** The name of a library folder that is, contains or is inside [handle] (the indexer would list the saved files as local songs), or null. */
export async function overlappingLibraryRoot(handle: FileSystemDirectoryHandle, roots: readonly LibraryRootRow[]): Promise<string | null> {
  for (const root of roots) {
    if (root.kind !== 'fsa' || !root.handle) continue;
    try {
      if ((await root.handle.isSameEntry(handle)) || (await root.handle.resolve(handle)) || (await handle.resolve(root.handle))) return root.name;
    } catch {
      /* a root that can't be compared is not an overlap we can prove */
    }
  }
  return null;
}

/** Chromium: pick the folder. Must be called straight from a click. Null when cancelled. */
export async function pickFolder(template: string): Promise<FolderRecord | null> {
  const picker = (window as unknown as { showDirectoryPicker(o?: object): Promise<FileSystemDirectoryHandle> }).showDirectoryPicker;
  try {
    const handle = await picker.call(window, { id: 'auden-saved-songs', mode: 'readwrite', startIn: 'music' });
    return { handle, name: handle.name, template, chosenAt: Date.now() };
  } catch {
    return null; // cancelled, or the browser refused the folder (a system folder)
  }
}
