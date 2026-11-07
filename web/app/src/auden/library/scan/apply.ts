import { notifyStores } from '../../db/notify';
import { localUriOf, parseLocalUri, rootUriPrefix } from '../../db/libraryDao';
import { openAudenDb, type TrackRow } from '../../db/schema';
import type { ScanArt, ScanEntry } from './protocol';
import { rowFromEntry } from './rows';

/**
 * Writes one batch from the Worker. Lookup and write share one transaction, so two scans of one root (two
 * tabs) cannot both decide a file is new and trip the unique `localUri` index; an existing row keeps its
 * id, so `auden:local:<id>` survives a re-index.
 */
export async function applyBatch(rootId: string, entries: ScanEntry[], art: ScanArt[]): Promise<{ added: number; changed: number }> {
  const db = await openAudenDb();
  const tx = db.transaction(['track', 'local_art', 'library_root'], 'readwrite');
  // A folder removed while its scan was running must not get rows back (they could never be played or removed).
  if (!(await tx.objectStore('library_root').get(rootId))) {
    await tx.done;
    return { added: 0, changed: 0 };
  }
  const tracks = tx.objectStore('track');
  const index = tracks.index('localUri');
  let added = 0;
  let changed = 0;
  for (const entry of entries) {
    const existing = await index.get(localUriOf(rootId, entry.relPath));
    const row = rowFromEntry(rootId, entry, existing);
    if (existing) {
      await tracks.put(row);
      changed++;
    } else {
      const { id: _id, ...fresh } = row;
      await tracks.add(fresh as TrackRow);
      added++;
    }
  }
  for (const cover of art) await tx.objectStore('local_art').put(cover);
  await tx.done;
  if (added || changed) notifyStores('track');
  if (art.length) notifyStores('local_art');
  return { added, changed };
}

/** Relative paths of every row the index holds for a root, with the size and mtime it saw. */
export async function knownFiles(rootId: string): Promise<Array<[string, number, number]>> {
  const db = await openAudenDb();
  const prefix = rootUriPrefix(rootId);
  const range = IDBKeyRange.bound(prefix, prefix + '￿');
  const rows = await db.getAllFromIndex('track', 'localUri', range);
  const out: Array<[string, number, number]> = [];
  for (const row of rows) {
    const parsed = row.localUri ? parseLocalUri(row.localUri) : null;
    if (parsed) out.push([parsed.relPath, row.sizeBytes ?? -1, row.modifiedAt ?? -1]);
  }
  return out;
}

/**
 * A file that left the folder. A row with a YouTube id stays (it is a streaming track that only lost its
 * file: playlists and stats point at it); any other row goes, with its playlist entries and plays.
 */
export async function dropFiles(rootId: string, relPaths: string[]): Promise<number> {
  if (!relPaths.length) return 0;
  const db = await openAudenDb();
  let dropped = 0;
  for (let i = 0; i < relPaths.length; i += 200) {
    const tx = db.transaction(['track', 'playlist_entry', 'play_event'], 'readwrite');
    const tracks = tx.objectStore('track');
    for (const relPath of relPaths.slice(i, i + 200)) {
      const row = await tracks.index('localUri').get(localUriOf(rootId, relPath));
      if (!row?.id) continue;
      dropped++;
      if (row.videoId) {
        await tracks.put({ ...row, localUri: null, path: null, folder: null, sizeBytes: null, modifiedAt: null, origin: 'YTM' });
        continue;
      }
      for (const store of ['playlist_entry', 'play_event'] as const) {
        const s = tx.objectStore(store);
        for (let c = await s.index('trackId').openKeyCursor(row.id); c; c = await c.continue()) await s.delete(c.primaryKey);
      }
      await tracks.delete(row.id);
    }
    await tx.done;
  }
  notifyStores('track', 'playlist_entry', 'play_event');
  return dropped;
}
