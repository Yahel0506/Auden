import { create } from 'zustand';
import type { LyricsTrack } from '@/lyrics/repository';

/**
 * Which song's saved lyrics are open. Kept at app level, not inside the menu that opened it: a menu
 * unmounts with its row (a search suggestion closing, a virtualized row recycled), and the dialog
 * must not die with it nor sit inside a row whose click plays the song.
 */
interface SavedLyricsDialogState {
  open: { songKey: string; track: LyricsTrack } | null;
  show: (songKey: string, track: LyricsTrack) => void;
  hide: () => void;
}

export const useSavedLyricsDialog = create<SavedLyricsDialogState>()((set) => ({
  open: null,
  show: (songKey, track) => set({ open: { songKey, track } }),
  hide: () => set({ open: null }),
}));
