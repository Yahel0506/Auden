import { create } from 'zustand';
import { persist } from 'zustand/middleware';

/**
 * Settings › Offline copies. Off until the listener turns it on: this feature fetches YouTube's audio files into
 * the browser, which is a choice with a terms-of-service edge (see Settings' text and the Phase 12 note), and it
 * has only been exercised against a stand-in stream, never a real download.
 */
interface OfflineSettingsState {
  enabled: boolean;
  /** Find and keep lyrics for what finishes downloading (the Phase 5 store; no job banner). */
  withLyrics: boolean;
  /** A playlist saved whole fetches its new songs too (Android syncDownloadedPlaylists, on by default; only matters while copies are on). */
  keepUpdated: boolean;
  setEnabled: (on: boolean) => void;
  setWithLyrics: (on: boolean) => void;
  setKeepUpdated: (on: boolean) => void;
}

/** On, as on Android (v1.12): a playlist saved whole stays saved. It only matters while offline copies are on. */
const KEEP_UPDATED_DEFAULT = true;

export const useOfflineSettings = create<OfflineSettingsState>()(
  persist(
    (set) => ({
      enabled: false,
      withLyrics: true,
      keepUpdated: KEEP_UPDATED_DEFAULT,
      setEnabled: (enabled) => set({ enabled }),
      setWithLyrics: (withLyrics) => set({ withLyrics }),
      setKeepUpdated: (keepUpdated) => set({ keepUpdated }),
    }),
    { name: 'auden:offline', version: 1, partialize: (s) => ({ enabled: s.enabled, withLyrics: s.withLyrics, keepUpdated: s.keepUpdated }),
      // An older build's value has no keepUpdated: it is on, as new installs have it.
      merge: (stored, current) => {
        const s = (stored ?? {}) as Partial<OfflineSettingsState>;
        return { ...current, enabled: s.enabled === true, withLyrics: s.withLyrics !== false, keepUpdated: s.keepUpdated ?? KEEP_UPDATED_DEFAULT };
      },
    },
  ),
);
