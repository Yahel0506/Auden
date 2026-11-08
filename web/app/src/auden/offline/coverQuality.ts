import type { OfflineItem, OfflineSong } from './types';

/** A saved cover narrower than this looks pixelated in the full-screen player. */
export const GOOD_COVER_PX = 400;

/** The width of a saved cover in pixels; 0 when it cannot be decoded. */
export async function coverWidth(blob: Blob): Promise<number> {
  try {
    const bitmap = await createImageBitmap(blob);
    const { width } = bitmap;
    bitmap.close();
    return width;
  } catch {
    return 0;
  }
}

export interface CoverUpgradeDeps {
  items(): Promise<OfflineItem[]>;
  putItem(item: OfflineItem): Promise<void>;
  /** The song's cover as a blob at a size the player can show well, or null. */
  fetchCover(url: string): Promise<Blob | null>;
  width(blob: Blob): Promise<number>;
  onUpgraded(): void;
}

/**
 * Copies saved before covers were kept at 544 px carry the thumbnail the list had (60 or 120 px). Each one found
 * narrower than [GOOD_COVER_PX] is fetched again at the good size and saved over, one at a time, so the full-screen
 * player has a sharp cover to read from the file. A cover that cannot be improved (no URL, the CDN refuses, the new
 * one is no wider) is left as it is. Resolves with how many were upgraded.
 */
export async function upgradeLowCovers(deps: CoverUpgradeDeps): Promise<number> {
  let upgraded = 0;
  for (const item of await deps.items().catch(() => [] as OfflineItem[])) {
    const url = (item as OfflineSong).thumbnailUrl;
    if (!item.cover || !url) continue;
    const before = await deps.width(item.cover);
    if (before >= GOOD_COVER_PX) continue;
    const better = await deps.fetchCover(url).catch(() => null);
    if (!better || (await deps.width(better)) <= before) continue;
    await deps.putItem({ ...item, cover: better });
    upgraded++;
  }
  if (upgraded > 0) deps.onUpgraded();
  return upgraded;
}
