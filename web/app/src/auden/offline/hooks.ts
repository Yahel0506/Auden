import { useEffect, useState } from 'react';
import { useExtension } from '@/extension/useExtension';
import { getOfflineRuntime } from './runtime';
import type { QueueView } from './queue';
import { useOfflineSettings } from './settings';
import type { OfflineItem } from './types';

/** The queue's rows and pause state, live. */
export function useOfflineQueue(): QueueView {
  const { queue } = getOfflineRuntime();
  const [view, setView] = useState<QueueView>(() => queue.view());
  useEffect(() => {
    const update = () => setView(queue.view());
    void queue.reload();
    update();
    return queue.subscribe(update);
  }, [queue]);
  return view;
}

/** The finished copies, live (this tab's and other tabs' changes). */
export function useOfflineItems(): OfflineItem[] | null {
  const { store, bus } = getOfflineRuntime();
  const [items, setItems] = useState<OfflineItem[] | null>(null);
  useEffect(() => {
    let alive = true;
    const load = () => void store.items().then((rows) => alive && setItems(rows)).catch(() => alive && setItems([]));
    load();
    const off = bus.subscribe(load);
    return () => {
      alive = false;
      off();
    };
  }, [store, bus]);
  return items;
}

export type OfflineStatus = 'kept' | 'queued' | 'none';

/** Whether [videoId] is kept, being fetched, or neither. */
export function useOfflineStatus(videoId: string): OfflineStatus {
  const items = useOfflineItems();
  const { rows } = useOfflineQueue();
  if (items?.some((i) => i.videoId === videoId)) return 'kept';
  return rows.some((r) => r.job.videoId === videoId && r.status !== 'failed') ? 'queued' : 'none';
}

/** The offer to save is on only when the listener switched the feature on, this browser can store files, and the extension can fetch. */
export function useOfflineAvailable(): boolean {
  const enabled = useOfflineSettings((s) => s.enabled);
  const ext = useExtension();
  const ready = ext.state === 'present' && ext.extension.info.capabilities.includes('yt');
  return enabled && ready && getOfflineRuntime().supported;
}
