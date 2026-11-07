import { queryClient } from '@/lib/queryClient';
import { subscribeToStores } from './notify';
import type { StoreName } from './schema';

/** Query keys for the library database: ['auden', <store>, ...]. */
export const audenKey = (store: string, ...rest: unknown[]) => ['auden', store, ...rest] as const;

/**
 * Queries keyed under one store that also read another: a playlist's entries are shown with their track rows, and
 * the playlist list carries counts and thumbnails. A change to the key store refetches its dependents too.
 */
export const DEPENDENT_STORES: Partial<Record<StoreName, readonly StoreName[]>> = {
  track: ['playlist_entry', 'playlist'],
  playlist_entry: ['playlist'],
};

/** Keeps queries over the database fresh: a write here or in another tab refetches them. Mount once. */
export function startAudenQuerySync(): () => void {
  // A scan or an identification run writes in bursts; the library stores refetch at most every 300 ms.
  const BURSTY = new Set<StoreName>(['track', 'local_art']);
  const timers = new Map<StoreName, ReturnType<typeof setTimeout>>();
  const invalidate = (store: StoreName) => {
    for (const s of [store, ...(DEPENDENT_STORES[store] ?? [])]) void queryClient.invalidateQueries({ queryKey: ['auden', s] });
  };
  const off = subscribeToStores((stores) => {
    for (const store of stores) {
      if (!BURSTY.has(store)) {
        invalidate(store);
      } else if (!timers.has(store)) {
        timers.set(
          store,
          setTimeout(() => {
            timers.delete(store);
            invalidate(store);
          }, 300),
        );
      }
    }
  });
  return () => {
    off();
    timers.forEach(clearTimeout);
  };
}
