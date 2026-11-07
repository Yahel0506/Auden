import { localArt } from '../db/libraryDao';
import { openAudenDb } from '../db/schema';
import { ART_SCHEME } from '../library/scan/diff';
import { setPlaylistCover } from './store';

/**
 * The 2x2 mosaic cover of a playlist (Android PlaylistCovers): the first four distinct thumbnails, only with at
 * least two, drawn 300 px tiles on a 600 px JPEG. Stored in `local_art` under a key that names the covers it was
 * made from, so a changed playlist makes a new key (never a stale image) and the old one is deleted with it.
 */

export const MOSAIC_TILE = 300;
export const MOSAIC_MIN = 2;
export const MOSAIC_MAX = 4;
const PLAYLIST_ART_PREFIX = 'playlist:';

/** The thumbnails a mosaic is made of: distinct, in order, at most four, or none when fewer than two. */
export function mosaicSources(thumbnails: ReadonlyArray<string | null | undefined>): string[] {
  const seen: string[] = [];
  for (const url of thumbnails) {
    if (!url || url.startsWith('blob:') || seen.includes(url)) continue;
    seen.push(url);
    if (seen.length === MOSAIC_MAX) break;
  }
  return seen.length >= MOSAIC_MIN ? seen : [];
}

/** A short stable hash of the sources (FNV-1a, 32 bit): the key changes exactly when the covers do. */
export function mosaicHash(sources: readonly string[]): string {
  let h = 0x811c9dc5;
  for (const ch of sources.join('\n')) {
    h ^= ch.codePointAt(0)!;
    h = Math.imul(h, 0x01000193) >>> 0;
  }
  return h.toString(16).padStart(8, '0');
}

export const mosaicKey = (playlistId: string, sources: readonly string[]): string => `${PLAYLIST_ART_PREFIX}${playlistId}:${mosaicHash(sources)}`;
export const artUrlForKey = (key: string): string => ART_SCHEME + encodeURIComponent(key);

/** Draws the mosaic. Null when fewer than two images could be read (a cross-origin image without CORS, a dead link). */
export async function drawMosaic(sources: readonly string[], fetchImage: (url: string) => Promise<ImageBitmap> = fetchBitmap): Promise<{ bytes: ArrayBuffer; mime: string } | null> {
  const tiles: ImageBitmap[] = [];
  for (const url of sources) {
    try {
      tiles.push(await fetchImage(url));
    } catch {
      // skipped
    }
  }
  if (tiles.length < MOSAIC_MIN) {
    tiles.forEach((t) => t.close());
    return null;
  }
  const size = MOSAIC_TILE * 2;
  const canvas = new OffscreenCanvas(size, size);
  const ctx = canvas.getContext('2d');
  if (!ctx) return null;
  for (let slot = 0; slot < 4; slot++) {
    const tile = tiles[slot % tiles.length]!;
    // Centre-crop to a square, as Android does.
    const side = Math.min(tile.width, tile.height);
    ctx.drawImage(tile, (tile.width - side) / 2, (tile.height - side) / 2, side, side, (slot % 2) * MOSAIC_TILE, Math.floor(slot / 2) * MOSAIC_TILE, MOSAIC_TILE, MOSAIC_TILE);
  }
  tiles.forEach((t) => t.close());
  const blob = await canvas.convertToBlob({ type: 'image/jpeg', quality: 0.88 });
  return { bytes: await blob.arrayBuffer(), mime: 'image/jpeg' };
}

async function fetchBitmap(url: string): Promise<ImageBitmap> {
  // A local song's cover lives in `local_art`, not on the network.
  if (url.startsWith(ART_SCHEME)) {
    const row = await localArt.get(decodeURIComponent(url.slice(ART_SCHEME.length)));
    if (!row) throw new Error('no such cover');
    return createImageBitmap(new Blob([row.bytes], { type: row.mime }));
  }
  // mode: 'cors' so a cross-origin image can't taint the canvas; a host without CORS rejects and is skipped.
  const response = await fetch(url, { mode: 'cors', referrerPolicy: 'no-referrer' });
  if (!response.ok) throw new Error(String(response.status));
  return createImageBitmap(await response.blob());
}

/**
 * Makes (or refreshes) a playlist's mosaic from [thumbnails]. A cloud copy's own cover (an http(s) coverUrl) is never
 * replaced. Called once per import or sync, not per song: every `local_art` write makes the UI re-read all covers.
 * The write goes through the store's `setPlaylistCover`, which re-reads the current cover inside its transaction: two
 * refreshes at once, or a playlist deleted while its cover is being drawn, leave no orphan image.
 */
export async function refreshMosaic(
  playlistId: string,
  thumbnails: ReadonlyArray<string | null | undefined>,
  draw: typeof drawMosaic = drawMosaic,
): Promise<string | null> {
  const db = await openAudenDb();
  const playlist = await db.get('playlist', playlistId);
  if (!playlist) return null;
  if (playlist.coverUrl && !playlist.coverUrl.startsWith(ART_SCHEME)) return playlist.coverUrl;
  const sources = mosaicSources(thumbnails);
  if (!sources.length) {
    if (playlist.coverUrl) await setPlaylistCover(playlistId, null);
    return null;
  }
  const key = mosaicKey(playlistId, sources);
  const url = artUrlForKey(key);
  if (playlist.coverUrl === url && (await localArt.get(key))) return url;
  const image = await draw(sources);
  if (!image) return playlist.coverUrl; // keep what it has
  return (await setPlaylistCover(playlistId, url, { key, ...image })) ? url : null;
}

/** Refreshes a playlist's mosaic from what it holds now (the caller need not wait for it). */
export async function refreshCoverFor(playlistId: string): Promise<void> {
  const { entriesOf } = await import('./store');
  const rows = (await entriesOf(playlistId)).map((i) => i.row?.thumbnailUrl);
  await refreshMosaic(playlistId, rows).catch(() => undefined);
}
