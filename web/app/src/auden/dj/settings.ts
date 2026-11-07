import { create } from 'zustand';
import { persist } from 'zustand/middleware';

/** Settings › Auden DJ (AppSettings.kt: dj_voice, dj_chatty, dj_language, dj_block_size). */
export type DjLanguage = 'es' | 'en';
export const BLOCK_SIZE = { min: 5, max: 12, default: 8 } as const;

interface DjSettings {
  /** Speak the lines (needs the voice proxy); off = the words appear on screen only. */
  voice: boolean;
  /** Also comment on a song in the middle of a set. */
  chatty: boolean;
  language: DjLanguage;
  blockSize: number;
  set: (patch: Partial<Pick<DjSettings, 'voice' | 'chatty' | 'language' | 'blockSize'>>) => void;
}

export const defaultLanguage = (): DjLanguage => (typeof navigator !== 'undefined' && navigator.language?.toLowerCase().startsWith('es') ? 'es' : 'en');
export const clampBlockSize = (n: number): number => (Number.isFinite(n) ? Math.min(BLOCK_SIZE.max, Math.max(BLOCK_SIZE.min, Math.round(n))) : BLOCK_SIZE.default);

export const useDjSettings = create<DjSettings>()(
  persist(
    (set) => ({
      voice: true,
      chatty: false,
      language: defaultLanguage(),
      blockSize: BLOCK_SIZE.default,
      set: (patch) => set({ ...patch, ...(patch.blockSize !== undefined ? { blockSize: clampBlockSize(patch.blockSize) } : {}) }),
    }),
    {
      name: 'auden:dj',
      version: 1,
      partialize: (s) => ({ voice: s.voice, chatty: s.chatty, language: s.language, blockSize: s.blockSize }),
      merge: (stored, current) => {
        const s = (stored ?? {}) as Partial<DjSettings>;
        return { ...current, voice: s.voice ?? current.voice, chatty: s.chatty ?? current.chatty, language: s.language === 'es' || s.language === 'en' ? s.language : current.language, blockSize: clampBlockSize(s.blockSize ?? BLOCK_SIZE.default) };
      },
    },
  ),
);
