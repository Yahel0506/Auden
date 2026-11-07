/**
 * Lyrics timing, as on the phone (SongLyricsOffset.kt, PlayerLyrics.kt). A song's own offset wins over
 * the global one; null means it follows the global. A positive offset shows the lyrics LATER: the
 * cursor is the playhead minus the offset, and a tap on a line seeks to its time plus the offset.
 */
export function effectiveOffset(own: number | null | undefined, global: number): number {
  return own ?? global;
}

export const cursorMs = (positionMs: number, offsetMs: number): number => Math.max(0, positionMs - offsetMs);
export const seekMs = (lineMs: number, offsetMs: number): number => Math.max(0, lineMs + offsetMs);

/** "+0.3 s" / "-1.2 s" / "0.0 s", as the phone's sheet shows it. */
export function formatOffset(ms: number): string {
  const seconds = (ms / 1000).toFixed(1);
  return ms > 0 ? `+${seconds} s` : `${seconds} s`;
}
