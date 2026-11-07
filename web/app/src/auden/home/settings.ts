import { create } from 'zustand';
import { persist } from 'zustand/middleware';
import { DEFAULT_WEIGHT } from './composer';
import { LOCAL_SHELF_KINDS, type LocalShelfKind } from './shelves';

/** Settings › Home: how much of the page is the listener's own, and which of their shelves appear (AppSettings.kt). */
interface HomeSettingsState {
  /** 0 = only the YouTube Music feed, 100 = every shelf of yours first. Steps of 5, as on the phone. */
  localWeight: number;
  enabled: Record<LocalShelfKind, boolean>;
  /** Home's Recently Played shelf comes off (Android hideRecents), the feed's own copy of it too. */
  hideRecents: boolean;
  /** Music videos come off the feed, with any shelf left with nothing else on it (Android hideHomeVideos). */
  hideVideos: boolean;
  /** Music videos are square wherever they show (Android squareVideoThumbnails). */
  squareVideoThumbnails: boolean;
  setLocalWeight: (weight: number) => void;
  setShelf: (kind: LocalShelfKind, on: boolean) => void;
  setHideRecents: (on: boolean) => void;
  setHideVideos: (on: boolean) => void;
  setSquareVideoThumbnails: (on: boolean) => void;
}

const allOn = (): Record<LocalShelfKind, boolean> => Object.fromEntries(LOCAL_SHELF_KINDS.map((k) => [k, true])) as Record<LocalShelfKind, boolean>;
export const clampWeight = (w: number): number => (Number.isFinite(w) ? Math.min(100, Math.max(0, Math.round(w / 5) * 5)) : DEFAULT_WEIGHT);

export const useHomeSettings = create<HomeSettingsState>()(
  persist(
    (set) => ({
      localWeight: DEFAULT_WEIGHT,
      enabled: allOn(),
      hideRecents: false,
      hideVideos: false,
      squareVideoThumbnails: false,
      setHideRecents: (hideRecents) => set({ hideRecents }),
      setHideVideos: (hideVideos) => set({ hideVideos }),
      setSquareVideoThumbnails: (squareVideoThumbnails) => set({ squareVideoThumbnails }),
      setLocalWeight: (w) => set({ localWeight: clampWeight(w) }),
      setShelf: (kind, on) => set((s) => ({ enabled: { ...s.enabled, [kind]: on } })),
    }),
    {
      name: 'auden:home',
      version: 1,
      partialize: (s) => ({
        localWeight: s.localWeight,
        enabled: s.enabled,
        hideRecents: s.hideRecents,
        hideVideos: s.hideVideos,
        squareVideoThumbnails: s.squareVideoThumbnails,
      }),
      // A stored value from an older build may miss a shelf kind or hold a bad weight: fall back per field.
      merge: (stored, current) => {
        const s = (stored ?? {}) as Partial<HomeSettingsState>;
        return {
          ...current,
          localWeight: clampWeight(s.localWeight ?? DEFAULT_WEIGHT),
          enabled: { ...allOn(), ...(s.enabled ?? {}) },
          hideRecents: s.hideRecents === true,
          hideVideos: s.hideVideos === true,
          squareVideoThumbnails: s.squareVideoThumbnails === true,
        };
      },
    },
  ),
);
