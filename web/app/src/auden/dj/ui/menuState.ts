import { create } from 'zustand';

/** Whether the DJ's menu (its button's glass menu) is open: the DJ's screen stays up while the listener is typing in it. */
export const useDjMenu = create<{ open: boolean }>(() => ({ open: false }));
