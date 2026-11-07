/**
 * Asks the browser not to evict the library under storage pressure. Called when the listener first
 * creates something worth keeping (a playlist, a music folder); harmless to call again.
 */
export async function requestPersistence(): Promise<boolean> {
  try {
    if (!navigator.storage?.persist) return false;
    if (await navigator.storage.persisted()) return true;
    return await navigator.storage.persist();
  } catch {
    return false;
  }
}
