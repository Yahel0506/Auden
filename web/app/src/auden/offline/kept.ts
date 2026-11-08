/** Which videoIds have an offline copy, in memory (filled by runtime.ts). Kept apart so Home and the DJ can ask without loading the downloader. */
export interface KeptFile {
  file: string;
  mime: string;
  /** The cover saved with the copy, when the CDN handed it over. */
  cover?: Blob | null;
}

/** A saved cover is shown while online only when it is sharp (coverQuality.ts GOOD_COVER_PX); otherwise the CDN's. */
const GOOD_PX = 400;
const coverPx = new Map<string, { size: number; px: number }>();

/** Records how wide a copy's saved cover is, once measured. */
export function setCoverPx(videoId: string, blob: Blob, px: number): void {
  coverPx.set(videoId, { size: blob.size, px });
}

const kept = new Map<string, KeptFile>();
/** The saved covers as object URLs, made when first asked for and let go when the copy is. */
const coverUrls = new Map<string, { blob: Blob; url: string }>();

export const hasOfflineCopy = (videoId: string): boolean => kept.has(videoId);
export const offlineFileOf = (videoId: string): KeptFile | undefined => kept.get(videoId);

/**
 * The cover that came with an offline copy, as a URL the page can show with no network; null when there is none.
 * One URL per copy for as long as the copy lasts: the store hands back a new Blob object on every read, and a URL
 * revoked because of that would leave the cover the player is showing blank.
 */
export function offlineCoverUrl(videoId: string): string | null {
  const blob = kept.get(videoId)?.cover;
  // Online, a blurry saved cover is worse than the CDN's: it is for the times there is no network.
  const measured = blob ? coverPx.get(videoId) : undefined;
  const sharp = !!blob && measured?.size === blob.size && measured.px >= GOOD_PX;
  if (blob && !sharp && (typeof navigator === 'undefined' || navigator.onLine !== false)) return null;
  if (!blob || typeof URL === 'undefined' || typeof URL.createObjectURL !== 'function') return null;
  const known = coverUrls.get(videoId);
  if (known) return known.url;
  const url = URL.createObjectURL(blob);
  coverUrls.set(videoId, { blob, url });
  return url;
}

export function replaceKept(entries: Iterable<[string, KeptFile]>): void {
  kept.clear();
  for (const [id, file] of entries) kept.set(id, file);
  // A cover whose copy is gone (or has no cover now) is let go; one whose copy is still here keeps its URL.
  for (const [id, held] of coverUrls) {
    if (!kept.get(id)?.cover) {
      URL.revokeObjectURL(held.url);
      coverUrls.delete(id);
    }
  }
  for (const id of coverPx.keys()) if (!kept.get(id)?.cover) coverPx.delete(id);
}
