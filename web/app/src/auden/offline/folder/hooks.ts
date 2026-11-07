import { useEffect } from 'react';
import { useOfflineAvailable, useOfflineItems } from '../hooks';
import { getOfflineRuntime } from '../runtime';
import { useOfflineSettings } from '../settings';
import { folderSupported } from './access';
import { loadFolder, refreshAccess, useFolder } from './state';

/** The folder state, loaded once and re-checked when the tab comes back (the listener may have allowed access elsewhere). */
export function useFolderState() {
  const state = useFolder();
  useEffect(() => {
    if (!useFolder.getState().loaded) void loadFolder();
    const onFocus = () => void refreshAccess();
    window.addEventListener('focus', onFocus);
    // Another tab chose, changed or forgot the folder: this one follows.
    const off = getOfflineRuntime().bus.subscribe(() => void loadFolder());
    return () => {
      window.removeEventListener('focus', onFocus);
      off();
    };
  }, []);
  return state;
}

/**
 * Whether "Save to Folder" is offered for a song: the experimental switch is on, this browser has the folder picker, a folder
 * was chosen, and the song is either already kept (no network needed) or can be downloaded (extension).
 */
export function useFolderOffer(kept: boolean): boolean {
  const enabled = useOfflineSettings((s) => s.enabled);
  const { record } = useFolderState();
  const canDownload = useOfflineAvailable();
  return enabled && folderSupported() && !!record && (kept || canDownload);
}

/** How many kept songs are waiting to be written to the folder. */
export function usePendingCount(): number {
  return (useOfflineItems() ?? []).filter((i) => i.folderPending).length;
}
