import { create } from 'zustand';
import type { Track } from '@/data/music/types';
import { libraryRoots } from '../../db/libraryDao';
import { requestPersistence } from '../../db/persist';
import { canKeepOffline } from '../actions';
import { getOfflineRuntime, songOf } from '../runtime';
import type { OfflineItem } from '../types';
import { allowFolder, folderAccess, folderSupported, overlappingLibraryRoot, pickFolder, type FolderAccess } from './access';
import { asDir } from './dirs';
import { DEFAULT_TEMPLATE, validTemplate } from './layout';
import type { FolderRecord } from './record';
import { lrcTextFor } from './sidecar';
import { saveToFolder, withFolderLock, type SaveReport } from './writer';

/**
 * The chosen folder as the UI and the queue see it, and the entry points that write to it. Everything that may show
 * a permission prompt (choose, allow, save from a click) is called straight from a click handler.
 */
interface FolderState {
  loaded: boolean;
  record: FolderRecord | null;
  access: FolderAccess;
  /** A library folder that is, holds or sits inside the chosen one: its saved files would be indexed as local songs. */
  overlap: string | null;
}

export const useFolder = create<FolderState>(() => ({ loaded: false, record: null, access: 'prompt', overlap: null }));

export const folderAvailable = (): boolean => folderSupported();

async function settle(record: FolderRecord | null): Promise<void> {
  useFolder.setState({
    loaded: true,
    record,
    access: record ? await folderAccess(record.handle) : 'prompt',
    overlap: record ? await overlappingLibraryRoot(record.handle, await libraryRoots.all().catch(() => [])) : null,
  });
}

/** Reads the stored folder and its permission (a query, never a prompt). */
export async function loadFolder(): Promise<void> {
  if (!folderSupported() && !import.meta.env.DEV) return useFolder.setState({ loaded: true });
  await settle(await getOfflineRuntime().store.folder().catch(() => null));
}

/** Re-checks the permission (the tab came back to the front, or the listener tapped Allow elsewhere). */
export async function refreshAccess(): Promise<void> {
  const { record } = useFolder.getState();
  if (record) useFolder.setState({ access: await folderAccess(record.handle) });
}

/** "Choose folder…": from a click. */
export async function chooseFolder(): Promise<boolean> {
  const template = useFolder.getState().record?.template ?? DEFAULT_TEMPLATE;
  const record = await pickFolder(template);
  if (!record) return false;
  await getOfflineRuntime().store.setFolder(record);
  await settle(record);
  getOfflineRuntime().bus.emit();
  void requestPersistence();
  // The saves that were waiting for a folder go now.
  void flushPending();
  return true;
}

/** "Allow access": from a click, with the handle already in memory so the request is the first await. */
export async function allowAccess(): Promise<boolean> {
  const record = useFolder.getState().record;
  if (!record) return false;
  const granted = await allowFolder(record.handle);
  await refreshAccess();
  if (granted) void flushPending();
  return granted;
}

export async function setTemplate(template: string): Promise<boolean> {
  const { record } = useFolder.getState();
  if (!record || !validTemplate(template)) return false;
  const next = { ...record, template };
  await getOfflineRuntime().store.setFolder(next);
  useFolder.setState({ record: next });
  getOfflineRuntime().bus.emit();
  return true;
}

export async function forgetFolder(): Promise<void> {
  await getOfflineRuntime().store.setFolder(null);
  useFolder.setState({ record: null, access: 'prompt', overlap: null });
  getOfflineRuntime().bus.emit();
}

/** Development only (the browser exercise): use this directory handle as the chosen folder. */
export async function injectFolder(handle: FileSystemDirectoryHandle, template = DEFAULT_TEMPLATE): Promise<void> {
  const record = { handle, name: handle.name, template, chosenAt: Date.now() };
  await getOfflineRuntime().store.setFolder(record);
  await settle(record);
}

/** Writes [items] now, if the permission is there. Stops, with the reason, when it isn't. */
export async function saveNow(items: readonly OfflineItem[]): Promise<SaveReport | { stopped: 'no-folder' | 'no-access'; results: [] }> {
  const rt = getOfflineRuntime();
  // The stored choice is the truth: another tab may have changed or forgotten the folder, or its layout, since this tab loaded it.
  const stored = await rt.store.folder().catch(() => null);
  const cached = useFolder.getState().record;
  if (stored?.chosenAt !== cached?.chosenAt || stored?.template !== cached?.template) await settle(stored);
  const record = stored;
  if (!record) return { stopped: 'no-folder', results: [] };
  const access = await folderAccess(record.handle);
  if (access !== 'granted') {
    useFolder.setState({ access });
    return { stopped: 'no-access', results: [] };
  }
  const report = await saveToFolder(items, {
    root: asDir(record.handle),
    template: record.template,
    files: rt.files,
    store: rt.store,
    lyrics: (item) => lrcTextFor(item),
    lock: withFolderLock,
  });
  if (report.results.length) rt.bus.emit();
  if (report.stopped === 'permission' || report.stopped === 'folder-missing') await refreshAccess();
  return report;
}

/** Items asked for a folder that have not been written (permission was missing, or the folder was not chosen yet). */
export async function flushPending(): Promise<void> {
  if (!useFolder.getState().loaded) await loadFolder();
  const items = (await getOfflineRuntime().store.items()).filter((i) => i.folderPending);
  if (items.length) await saveNow(items);
}

/** A download just finished with a folder wish: write it if the permission allows, else it waits (the manager says so). */
export async function autoSave(item: OfflineItem): Promise<void> {
  if (!useFolder.getState().loaded) await loadFolder();
  await saveNow([item]).catch(() => undefined);
}

/** Lyrics have just been looked up for these songs: a song already in the folder gets its `.lrc` now. */
export async function refreshSidecars(videoIds: readonly string[]): Promise<void> {
  if (!useFolder.getState().loaded) await loadFolder();
  const wanted = new Set(videoIds);
  const items = (await getOfflineRuntime().store.items()).filter((i) => wanted.has(i.videoId) && i.folder && !i.folder.lrc);
  if (items.length) await saveNow(items).catch(() => undefined);
}

export type FolderOutcome =
  | { kind: 'no-folder' }
  | { kind: 'no-access' }
  | { kind: 'ok'; saved: number; already: number; failed: { title: string; reason: string }[]; queued: number; stopped: string | null };

/**
 * "Save to Folder" for songs, from a click: the ones already kept are written now, the ones on their way are
 * flagged to follow, the rest are queued with the flag. The permission request is the first await.
 */
export async function saveSongsToFolder(tracks: readonly Track[]): Promise<FolderOutcome> {
  const record = useFolder.getState().record;
  if (!record) return { kind: 'no-folder' };
  const granted = await allowFolder(record.handle);
  await refreshAccess();
  if (!granted) return { kind: 'no-access' };
  const rt = getOfflineRuntime();
  const songs = tracks.filter(canKeepOffline);
  const ids = new Set(songs.map((t) => t.videoId));
  const kept = (await rt.store.items()).filter((i) => ids.has(i.videoId));
  const keptIds = new Set(kept.map((i) => i.videoId));
  const rest = songs.filter((t) => !keptIds.has(t.videoId)).map(songOf);
  const queued = rest.length ? (await rt.queue.enqueue(rest, { toFolder: true })).added : 0;
  let saved = 0;
  let already = 0;
  let stopped: string | null = null;
  const failed: { title: string; reason: string }[] = [];
  if (kept.length) {
    const report = await saveNow(kept);
    stopped = report.stopped;
    for (const r of report.results) {
      if (r.status === 'saved') saved++;
      else if (r.status === 'already') already++;
      else failed.push({ title: kept.find((i) => i.videoId === r.videoId)?.title ?? r.videoId, reason: r.reason ?? 'Failed' });
    }
  }
  return { kind: 'ok', saved, already, failed, queued, stopped };
}

/** "Save all offline songs to the folder": from a click; every kept song not yet there (and a missing `.lrc` for those that are). */
export async function saveKeptToFolder(): Promise<FolderOutcome> {
  const record = useFolder.getState().record;
  if (!record) return { kind: 'no-folder' };
  const granted = await allowFolder(record.handle);
  await refreshAccess();
  if (!granted) return { kind: 'no-access' };
  const items = await getOfflineRuntime().store.items();
  const report = await saveNow(items);
  let saved = 0;
  let already = 0;
  const failed: { title: string; reason: string }[] = [];
  for (const r of report.results) {
    if (r.status === 'saved') saved++;
    else if (r.status === 'already') already++;
    else failed.push({ title: items.find((i) => i.videoId === r.videoId)?.title ?? r.videoId, reason: r.reason ?? 'Failed' });
  }
  return { kind: 'ok', saved, already, failed, queued: 0, stopped: report.stopped };
}
