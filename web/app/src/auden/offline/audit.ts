import type { FileStore, StoredFile } from './files';
import type { OfflineStore } from './store';
import type { OfflineItem } from './types';

/**
 * What is in the offline folder and whether it matches the records (the intent of Android's DownloadAudit, for
 * a flat folder keyed by video id: there is no layout to be "misplaced" in, and a second copy of a song is
 * the listener's own choice of another video, so there are two questions, not three):
 *  - **Orphans**: files no record points to (a write that finished but whose record could not be saved, a
 *    cleared database with a surviving folder).
 *  - **Broken**: records whose file is gone (the browser evicted it, the folder was cleared) or is not the
 *    size that was saved (a damaged copy that must not play).
 */
export interface AuditReport {
  orphans: StoredFile[];
  broken: { item: OfflineItem; why: 'missing' | 'size' }[];
  keptBytes: number;
  orphanBytes: number;
}

/** [busy] are files a download may be writing right now (listed with no content yet): never reported. */
export async function auditOffline(store: Pick<OfflineStore, 'items'>, files: Pick<FileStore, 'list'>, busy: ReadonlySet<string> = new Set()): Promise<AuditReport> {
  const [items, listed] = await Promise.all([store.items(), files.list()]);
  const byName = new Map(listed.map((f) => [f.name, f]));
  const referenced = new Set(items.map((i) => i.file));
  const orphans = listed.filter((f) => !referenced.has(f.name) && !busy.has(f.name.replace(/\.[^.]+$/, '')));
  const broken: AuditReport['broken'] = [];
  let keptBytes = 0;
  for (const item of items) {
    const file = byName.get(item.file);
    if (!file) broken.push({ item, why: 'missing' });
    else if (file.bytes !== item.bytes) broken.push({ item, why: 'size' });
    else keptBytes += file.bytes;
  }
  return { orphans, broken, keptBytes, orphanBytes: orphans.reduce((n, f) => n + f.bytes, 0) };
}

/** Deletes the orphan files and the records that point at nothing usable (a damaged file is deleted too). */
export async function repairOffline(report: AuditReport, store: Pick<OfflineStore, 'deleteItem'>, files: Pick<FileStore, 'remove'>): Promise<{ files: number; records: number }> {
  for (const orphan of report.orphans) await files.remove(orphan.name);
  for (const { item, why } of report.broken) {
    if (why === 'size') await files.remove(item.file);
    await store.deleteItem(item.videoId);
  }
  return { files: report.orphans.length, records: report.broken.length };
}

export interface UsageGroup {
  key: string;
  name: string;
  songs: number;
  bytes: number;
  items: OfflineItem[];
}

/** The copies grouped by album or artist, biggest first (Android's "space used" sheet). Songs with no album group under "Singles". */
export function usageBy(items: readonly OfflineItem[], by: 'album' | 'artist'): UsageGroup[] {
  const groups = new Map<string, UsageGroup>();
  for (const item of items) {
    const name = (by === 'album' ? item.album : item.artist.split(/,| & /)[0]?.trim()) || (by === 'album' ? 'Singles' : 'Unknown artist');
    const key = by === 'album' ? `${item.albumId ?? name}` : name;
    const group = groups.get(key) ?? { key, name, songs: 0, bytes: 0, items: [] };
    group.songs++;
    group.bytes += item.bytes;
    group.items.push(item);
    groups.set(key, group);
  }
  return [...groups.values()].sort((a, b) => b.bytes - a.bytes || a.name.localeCompare(b.name));
}

export function formatBytes(bytes: number): string {
  if (bytes < 1024) return `${bytes} B`;
  const units = ['KB', 'MB', 'GB', 'TB'];
  let value = bytes / 1024;
  let i = 0;
  while (value >= 1024 && i < units.length - 1) (value /= 1024, i++);
  return `${value >= 100 ? Math.round(value) : value.toFixed(1)} ${units[i]}`;
}
