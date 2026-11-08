/**
 * The real cover of songs that arrived with none (the phone leaves out an artwork that is a file on it; some queue rows
 * never had one), found from YouTube Music by video id and kept for the session. The player reads it synchronously
 * (state/player.ts hintOf), so a cover found once stays on screen across the mirror's refreshes.
 */
const covers = new Map<string, string>();

export const resolvedCoverOf = (videoId: string): string | null => covers.get(videoId) ?? null;
export const rememberCover = (videoId: string, url: string): void => void covers.set(videoId, url);
export const forgetCovers = (): void => covers.clear();
