import { create } from 'zustand';
import type { Track } from '@/data/music/types';

/**
 * Which Spotify row's match is being fixed. At app level, not inside the row's menu: a menu unmounts with
 * its row (a list scrolled away, a rematch swapping the row), and the dialog must not go with it.
 */
interface MatchDialogState {
  open: Track | null;
  show: (row: Track) => void;
  hide: () => void;
}

export const useMatchDialog = create<MatchDialogState>()((set) => ({
  open: null,
  show: (row) => set({ open: row }),
  hide: () => set({ open: null }),
}));
