import { create } from 'zustand';
import { libraryRoots } from '../db/libraryDao';
import { requestPersistence } from '../db/persist';
import type { LibraryRootRow } from '../db/schema';

/**
 * Folder access for this session. Chromium keeps a directory handle per root and re-asks permission with a
 * tap (`reconnect`, called from a click: requestPermission needs the gesture, so it runs before any await).
 * Firefox has no handles: a root there is a name plus the index it left, and its files exist only while
 * this page holds the `File`s the user picked (`sessionFiles`).
 */

export type RootAccess = 'granted' | 'prompt' | 'files' | 'offline';

/** Files picked with `<input webkitdirectory>` this session: rootId → relPath → File. */
export const sessionFiles = new Map<string, Map<string, File>>();

export const fsaSupported = (): boolean => typeof window !== 'undefined' && 'showDirectoryPicker' in window;

interface RootsState {
  roots: LibraryRootRow[];
  access: Record<string, RootAccess>;
  loaded: boolean;
}
export const useRoots = create<RootsState>(() => ({ roots: [], access: {}, loaded: false }));

type PermissionHandle = FileSystemDirectoryHandle & {
  queryPermission(d: { mode: 'read' }): Promise<PermissionState>;
  requestPermission(d: { mode: 'read' }): Promise<PermissionState>;
};

/** Query only, never request: this runs without a gesture (a crossfade, a queue advance). */
export async function accessOf(root: LibraryRootRow): Promise<RootAccess> {
  if (root.kind === 'files') return sessionFiles.has(root.id) ? 'files' : 'offline';
  if (!root.handle) return 'offline';
  try {
    return (await (root.handle as PermissionHandle).queryPermission({ mode: 'read' })) === 'granted' ? 'granted' : 'prompt';
  } catch {
    return 'offline';
  }
}

const listeners = new Set<() => void>();
/** Fires after the roots or their access changed (the link index rebuilds its notion of "usable"). */
export function onRootsChanged(listener: () => void): () => void {
  listeners.add(listener);
  return () => listeners.delete(listener);
}

export async function refreshRoots(): Promise<void> {
  const roots = await libraryRoots.all();
  const entries = await Promise.all(roots.map(async (r) => [r.id, await accessOf(r)] as const));
  useRoots.setState({ roots, access: Object.fromEntries(entries), loaded: true });
  listeners.forEach((l) => l());
}

/** Whether the root's files can be read right now. */
export function isUsable(rootId: string): boolean {
  const a = useRoots.getState().access[rootId];
  return a === 'granted' || a === 'files';
}

const newId = (): string => (crypto.randomUUID ? crypto.randomUUID().slice(0, 8) : Math.random().toString(36).slice(2, 10));

/** Chromium: pick a folder. Must be called straight from a click. Returns null when cancelled or a duplicate. */
export async function addFolder(): Promise<LibraryRootRow | { duplicate: string } | null> {
  const picker = (window as unknown as { showDirectoryPicker(o?: object): Promise<FileSystemDirectoryHandle> }).showDirectoryPicker;
  let handle: FileSystemDirectoryHandle;
  try {
    handle = await picker.call(window, { id: 'auden-music', mode: 'read' });
  } catch {
    return null; // cancelled
  }
  // The same folder twice, or a folder inside / around another, would index every song twice.
  for (const root of await libraryRoots.all()) {
    if (root.kind !== 'fsa' || !root.handle) continue;
    if (await root.handle.isSameEntry(handle)) return { duplicate: root.name };
    if ((await root.handle.resolve(handle)) || (await handle.resolve(root.handle))) return { duplicate: root.name };
  }
  const row: LibraryRootRow = { id: newId(), name: handle.name, kind: 'fsa', handle, addedAt: Date.now(), scannedAt: null };
  await libraryRoots.put(row);
  void requestPersistence();
  await refreshRoots();
  return row;
}

/** Chromium: the "Reconectar carpeta" tap. */
export async function reconnect(root: LibraryRootRow): Promise<boolean> {
  if (root.kind !== 'fsa' || !root.handle) return false;
  const result = await (root.handle as PermissionHandle).requestPermission({ mode: 'read' });
  await refreshRoots();
  return result === 'granted';
}

/**
 * Firefox: the files of a picked folder. Reconnecting binds the pick to an existing root, but only when the
 * folder has the same name (a different folder is refused). Adding never joins a root by name: that would let
 * any other folder called "Music" take over an existing index; a name already in use asks for Reconnect instead.
 */
export async function adoptPickedFiles(files: File[], reconnectRootId?: string): Promise<LibraryRootRow | { duplicate: string } | null> {
  const top = files[0]?.webkitRelativePath.split('/')[0];
  if (!top) return null;
  const roots = await libraryRoots.all();
  let root: LibraryRootRow | undefined;
  if (reconnectRootId) {
    root = roots.find((r) => r.id === reconnectRootId);
    if (!root || root.name !== top) return null;
  } else {
    const same = roots.find((r) => r.kind === 'files' && r.name === top);
    if (same) return { duplicate: same.name };
    root = { id: newId(), name: top, kind: 'files', addedAt: Date.now(), scannedAt: null };
    await libraryRoots.put(root);
    void requestPersistence();
  }
  const map = new Map<string, File>();
  for (const file of files) {
    const rel = file.webkitRelativePath.split('/').slice(1).join('/');
    if (rel) map.set(rel, file);
  }
  sessionFiles.set(root.id, map);
  await refreshRoots();
  return root;
}

export async function removeFolder(rootId: string): Promise<void> {
  sessionFiles.delete(rootId);
  await libraryRoots.remove(rootId);
  await refreshRoots();
}
