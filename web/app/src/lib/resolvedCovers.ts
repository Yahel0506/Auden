/**
 * The real cover of songs that arrived with none (the phone leaves out an artwork that is a file on it; some queue rows
 * never had one), found from YouTube Music by video id and kept for the session. The player reads it synchronously
 * (state/player.ts hintOf), so a cover found once stays on screen across the mirror's refreshes.
 */
const covers = new Map<string, string>();

export const resolvedCoverOf = (videoId: string): string | null => covers.get(videoId) ?? null;
let version = 0;
const listeners = new Set<() => void>();
const changed = () => {
  version++;
  for (const l of listeners) l();
};

export const rememberCover = (videoId: string, url: string): void => {
  if (covers.get(videoId) === url) return;
  covers.set(videoId, url);
  changed();
};
export const forgetCovers = (): void => {
  covers.clear();
  changed();
};

/** For useSyncExternalStore: rows re-render when a cover turns up. */
export const subscribeCovers = (listener: () => void): (() => void) => {
  listeners.add(listener);
  return () => void listeners.delete(listener);
};
export const coversVersion = (): number => version;
