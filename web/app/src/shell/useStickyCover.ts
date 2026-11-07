import { useEffect, useRef, useState } from 'react';

/** How long the new cover takes to fade in (`artworkLayer` in FullScreenPlayer: duration-300). */
export const COVER_FADE_MS = 300;

/**
 * The cover showing just before the current one, kept UNDER it until the current one is fully visible: the
 * new cover mounts at zero opacity and fades in, so with nothing beneath it the artwork would dip to empty
 * at every track change. It goes once the fade has run (not at `load`, when the new cover is still
 * invisible), at once if the new cover fails to load (the old artwork under a new title would be wrong),
 * and a timeout, not `transitionend`, ends it so it still goes under reduced motion or in a hidden tab.
 */
export function useStickyCover(url: string | null): { previousCover: string | null; coverLoaded: () => void; coverFailed: () => void } {
  const [state, setState] = useState<{ current: string | null; previous: string | null }>({ current: url, previous: null });
  if (state.current !== url) {
    // Adjusted during render, from props: React re-renders at once with no frame in between.
    setState({ current: url, previous: state.current });
  }
  const timer = useRef<ReturnType<typeof setTimeout> | undefined>(undefined);
  useEffect(() => () => clearTimeout(timer.current), []);
  // A new track before the fade ends: the pending release belongs to the cover that has been replaced.
  useEffect(() => () => clearTimeout(timer.current), [url]);

  const release = () => setState((s) => (s.current === url && s.previous ? { ...s, previous: null } : s));
  return {
    previousCover: state.previous,
    coverLoaded: () => {
      clearTimeout(timer.current);
      timer.current = setTimeout(release, COVER_FADE_MS + 40);
    },
    coverFailed: () => {
      clearTimeout(timer.current);
      release();
    },
  };
}
