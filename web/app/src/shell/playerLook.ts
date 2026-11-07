import { create } from 'zustand';
import { persist } from 'zustand/middleware';

/** What the main player stands on below the artwork (Android PlayerTheme: DEFAULT / LEGACY / SIMPLE). */
export type PlayerTheme = 'continuation' | 'colors' | 'blur';
export const PLAYER_THEMES: readonly PlayerTheme[] = ['continuation', 'colors', 'blur'];
export const DEFAULT_THEME: PlayerTheme = 'continuation';
export const isPlayerTheme = (v: unknown): v is PlayerTheme => PLAYER_THEMES.includes(v as PlayerTheme);

interface PlayerLookState {
  theme: PlayerTheme;
  /** Album and playlist covers run edge to edge and dissolve into the page (Android fullBleedDetailArtwork; narrow widths only). */
  fullBleedCovers: boolean;
  setTheme: (theme: PlayerTheme) => void;
  setFullBleedCovers: (on: boolean) => void;
}

/**
 * Settings that change how the player and the album pages look. Not the player's own state (state/player.ts keeps
 * `bitchord:player`): this is only appearance, and a stored value from an older build falls back per field.
 */
export const usePlayerLook = create<PlayerLookState>()(
  persist(
    (set) => ({
      theme: DEFAULT_THEME,
      fullBleedCovers: false,
      setTheme: (theme) => set({ theme }),
      setFullBleedCovers: (fullBleedCovers) => set({ fullBleedCovers }),
    }),
    {
      name: 'auden:look',
      version: 1,
      partialize: (s) => ({ theme: s.theme, fullBleedCovers: s.fullBleedCovers }),
      merge: (stored, current) => {
        const s = (stored ?? {}) as Partial<PlayerLookState>;
        return { ...current, theme: isPlayerTheme(s.theme) ? s.theme : DEFAULT_THEME, fullBleedCovers: s.fullBleedCovers === true };
      },
    },
  ),
);
