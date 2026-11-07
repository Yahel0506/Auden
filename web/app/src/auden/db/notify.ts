import type { StoreName } from './schema';

/**
 * Tells other code (and other tabs) that a store changed. BroadcastChannel does not deliver to the
 * tab that posted, so a write notifies local listeners itself and then broadcasts.
 */

type Listener = (stores: readonly StoreName[]) => void;
const listeners = new Set<Listener>();

const CHANNEL = 'auden-db';
let channel: BroadcastChannel | null | undefined;

function getChannel(): BroadcastChannel | null {
  if (channel === undefined) {
    try {
      channel = typeof BroadcastChannel === 'undefined' ? null : new BroadcastChannel(CHANNEL);
      if (channel) {
        channel.onmessage = (event: MessageEvent<readonly StoreName[]>) => {
          if (Array.isArray(event.data)) listeners.forEach((l) => l(event.data));
        };
      }
    } catch {
      channel = null;
    }
  }
  return channel;
}

export function subscribeToStores(listener: Listener): () => void {
  getChannel();
  listeners.add(listener);
  return () => listeners.delete(listener);
}

export function notifyStores(...stores: StoreName[]): void {
  listeners.forEach((l) => l(stores));
  getChannel()?.postMessage(stores);
}
