// Port of dev/auden/library/SongIdentity.kt (same / seconds) and
// dev/auden/library/io/PlaylistMatcher.kt (normalize).

const LENGTH_TOLERANCE_SEC = 3;

export interface IdentitySong {
  videoId?: string | null;
  localUri?: string | null;
  title: string;
  artist: string;
  durationSec?: number | null;
}

/** Lower-case, accent-free, without bracketed credits or punctuation. */
export function normalizeKey(s: string): string {
  return s
    .normalize('NFD')
    .replace(/\p{M}+/gu, '')
    .toLowerCase()
    .replace(/[(\[].*?[)\]]/g, ' ')
    .replace(/[^\p{L}\p{N}]+/gu, ' ')
    .trim();
}

/** "3:45" or "1:02:03" in seconds, or null when blank, malformed or zero. */
export function parseDurationText(text?: string | null): number | null {
  if (text == null || text.trim() === '') return null;
  const parts = text.trim().split(':');
  let total = 0;
  for (const part of parts) {
    if (!/^[+-]?\d+$/.test(part)) return null;
    total = total * 60 + Number(part);
  }
  return total > 0 ? total : null;
}

/**
 * Whether two rows are the same recording. Same non-blank videoId, or same localUri,
 * or: normalized titles equal and non-empty, artists non-empty and equal or one
 * containing the other, and (when both durations are known) within 3 seconds.
 */
export function sameSong(a: IdentitySong, b: IdentitySong): boolean {
  if (a.videoId != null && a.videoId.trim() !== '' && a.videoId === b.videoId) return true;
  if (a.localUri != null && a.localUri === b.localUri) return true;

  const title = normalizeKey(a.title);
  if (title === '' || title !== normalizeKey(b.title)) return false;

  const artistA = normalizeKey(a.artist);
  const artistB = normalizeKey(b.artist);
  if (artistA === '' || artistB === '') return false;
  if (artistA !== artistB && !artistA.includes(artistB) && !artistB.includes(artistA)) return false;

  const lengthA = a.durationSec != null && a.durationSec > 0 ? a.durationSec : null;
  const lengthB = b.durationSec != null && b.durationSec > 0 ? b.durationSec : null;
  return lengthA == null || lengthB == null || Math.abs(lengthA - lengthB) <= LENGTH_TOLERANCE_SEC;
}
