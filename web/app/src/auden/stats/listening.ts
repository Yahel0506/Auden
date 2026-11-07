import { notifyStores } from '../db/notify';
import { openAudenDb, type StoredBucket } from '../db/schema';
import { mergeBucket, type Pending } from './bucket';

/** The listening store: one StoredBucket per month. */

/**
 * Bumped by a backup restore, which REPLACES the listening: what was collected before it (a recorder's
 * pending batch, or one already taken and waiting its turn to be written) must not be merged into what the
 * restore wrote. A flush carries the generation it was taken in and is dropped when that has passed.
 */
let generation = 0;
const discarders = new Set<() => void>();

export const listeningGeneration = (): number => generation;

/** Called by a restore just before its transaction opens. Up to one batch (about thirty seconds) is lost. */
export function discardPendingListening(): void {
  generation++;
  discarders.forEach((d) => d());
}

/** The recorder registers how to drop what it holds in memory. */
export function onDiscardPendingListening(discard: () => void): () => void {
  discarders.add(discard);
  return () => discarders.delete(discard);
}

/**
 * Adds [pending] to the stored months in ONE readwrite transaction. The read and the write of a
 * month share it, with no other await between them (IndexedDB commits when it idles), so two tabs
 * flushing at once both land. Months older than three years are dropped, as on the phone.
 */
export async function flushListening(pending: Pending, takenIn: number = generation): Promise<void> {
  if (pending.size === 0) return;
  const db = await openAudenDb();
  // Checked in the same tick the transaction is created: a restore's transaction, created after the bump, is
  // ordered after any flush that got past here, and replaces what it wrote.
  if (takenIn !== generation) return;
  const tx = db.transaction('listening', 'readwrite');
  const store = tx.store;
  for (const bucket of pending.values()) {
    const existing = await store.get(bucket.month);
    await store.put(mergeBucket(existing, bucket));
  }
  const months = await store.getAllKeys();
  if (months.length > KEEP_MONTHS) {
    for (const month of months.slice(0, months.length - KEEP_MONTHS)) await store.delete(month);
  }
  await tx.done;
  notifyStores('listening');
}

const KEEP_MONTHS = 36;

export async function listeningMonths(): Promise<StoredBucket[]> {
  return (await openAudenDb()).getAll('listening');
}
