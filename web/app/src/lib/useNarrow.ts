import { useSyncExternalStore } from 'react';

/** Tailwind's `max-md`: the window is narrower than 768px (a phone, or a narrow window). */
const query = typeof matchMedia === 'function' ? matchMedia('(max-width: 47.999rem)') : null;

export function useNarrow(): boolean {
  return useSyncExternalStore(
    (onChange) => {
      query?.addEventListener('change', onChange);
      return () => query?.removeEventListener('change', onChange);
    },
    () => query?.matches ?? false,
  );
}
