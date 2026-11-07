import { openDB, type DBSchema, type IDBPDatabase } from 'idb';
import type { FollowedMap, OfflineItem, OfflineJob, PausedState } from './types';
import type { FolderRecord } from './folder/record';

/**
 * Its own IndexedDB (`auden-offline`), not a store in AudenDb: the queue and the copies are this browser's
 * own, they are not part of a backup, and keeping them apart means AudenDb's version (and its migration
 * and "database blocked" paths) is untouched by this feature.
 */
interface OfflineSchema extends DBSchema {
  job: { key: string; value: OfflineJob };
  item: { key: string; value: OfflineItem };
  /** Out-of-line keys: `paused`, `folder` and `followed` (no schema change, so no version bump: a v1 tab left open must not block this one). */
  meta: { key: string; value: PausedState | FolderRecord | FollowedMap | null };
}

export const OFFLINE_DB = 'auden-offline';
export const OFFLINE_DB_VERSION = 1;

export type OfflineDb = IDBPDatabase<OfflineSchema>;

let opened: Promise<OfflineDb> | null = null;

export function openOfflineDb(name = OFFLINE_DB): Promise<OfflineDb> {
  const make = () =>
    openDB<OfflineSchema>(name, OFFLINE_DB_VERSION, {
      upgrade(db) {
        db.createObjectStore('job', { keyPath: 'videoId' });
        db.createObjectStore('item', { keyPath: 'videoId' });
        db.createObjectStore('meta');
      },
    });
  if (name !== OFFLINE_DB) return make();
  opened ??= make().catch((error: unknown) => {
    opened = null;
    throw error;
  });
  return opened;
}

/** The data layer the queue and the views share; every call opens (or reuses) the database. */
export interface OfflineStore {
  jobs(): Promise<OfflineJob[]>;
  putJob(job: OfflineJob): Promise<void>;
  putJobs(jobs: OfflineJob[]): Promise<void>;
  deleteJob(videoId: string): Promise<void>;
  deleteJobs(videoIds: string[]): Promise<void>;
  items(): Promise<OfflineItem[]>;
  item(videoId: string): Promise<OfflineItem | undefined>;
  /** Writes the finished copy and removes its job in one transaction. */
  finish(item: OfflineItem): Promise<void>;
  /** Replaces one item (its folder fields changed). */
  putItem(item: OfflineItem): Promise<void>;
  deleteItem(videoId: string): Promise<void>;
  folder(): Promise<FolderRecord | null>;
  setFolder(record: FolderRecord | null): Promise<void>;
  /** Playlists saved whole, whose new songs are kept up to date (keepUpdated.ts). */
  followed(): Promise<FollowedMap>;
  setFollowed(map: FollowedMap): Promise<void>;
  paused(): Promise<PausedState | null>;
  setPaused(state: PausedState | null): Promise<void>;
}

export function dbStore(open: () => Promise<OfflineDb> = openOfflineDb): OfflineStore {
  return {
    async jobs() {
      return (await open()).getAll('job');
    },
    async putJob(job) {
      await (await open()).put('job', job);
    },
    async putJobs(jobs) {
      const tx = (await open()).transaction('job', 'readwrite');
      await Promise.all([...jobs.map((j) => tx.store.put(j)), tx.done]);
    },
    async deleteJob(videoId) {
      await (await open()).delete('job', videoId);
    },
    async deleteJobs(videoIds) {
      const tx = (await open()).transaction('job', 'readwrite');
      await Promise.all([...videoIds.map((id) => tx.store.delete(id)), tx.done]);
    },
    async items() {
      return (await open()).getAll('item');
    },
    async item(videoId) {
      return (await open()).get('item', videoId);
    },
    async finish(item) {
      const tx = (await open()).transaction(['item', 'job'], 'readwrite');
      await Promise.all([tx.objectStore('item').put(item), tx.objectStore('job').delete(item.videoId), tx.done]);
    },
    async putItem(item) {
      await (await open()).put('item', item);
    },
    async folder() {
      return ((await (await open()).get('meta', 'folder')) as FolderRecord | null | undefined) ?? null;
    },
    async setFolder(record) {
      const db = await open();
      if (record) await db.put('meta', record, 'folder');
      else await db.delete('meta', 'folder');
    },
    async deleteItem(videoId) {
      await (await open()).delete('item', videoId);
    },
    async followed() {
      return ((await (await open()).get('meta', 'followed')) as FollowedMap | null | undefined) ?? {};
    },
    async setFollowed(map) {
      const db = await open();
      if (Object.keys(map).length) await db.put('meta', map, 'followed');
      else await db.delete('meta', 'followed');
    },
    async paused() {
      return ((await (await open()).get('meta', 'paused')) as PausedState | null | undefined) ?? null;
    },
    async setPaused(state) {
      const db = await open();
      if (state) await db.put('meta', state, 'paused');
      else await db.delete('meta', 'paused');
    },
  };
}

/** Tells every tab (and this one) that the queue or the copies changed. */
export interface ChangeBus {
  emit(): void;
  subscribe(listener: () => void): () => void;
}

export function changeBus(name = 'auden-offline'): ChangeBus {
  const listeners = new Set<() => void>();
  const channel = typeof BroadcastChannel !== 'undefined' ? new BroadcastChannel(name) : null;
  const fire = () => listeners.forEach((l) => l());
  channel?.addEventListener('message', fire);
  return {
    emit() {
      channel?.postMessage(1);
      fire();
    },
    subscribe(listener) {
      listeners.add(listener);
      return () => void listeners.delete(listener);
    },
  };
}
