import type { MusicItem, Shelf } from '@/data/music/types';

/** The title YouTube Music gives its own Recently Played shelf (and the one Home builds from the history). */
export const RECENT_TITLE = 'Recently Played';

export const isRecentShelf = (shelf: Shelf): boolean => shelf.title.trim().toLowerCase() === RECENT_TITLE.toLowerCase();

const isVideoItem = (item: MusicItem): boolean => item.kind === 'video';

/**
 * The feed without its music videos (Android `List<HomeShelf>.withoutVideos`): a video row comes off a mixed shelf, and a
 * shelf left with nothing, or laid out as a video shelf, goes whole even if a row was not marked as a video.
 */
export function withoutVideos(shelves: readonly Shelf[]): Shelf[] {
  return shelves
    .filter((shelf) => shelf.layout !== 'video')
    .map((shelf) => (shelf.items.some(isVideoItem) ? { ...shelf, items: shelf.items.filter((item) => !isVideoItem(item)) } : shelf))
    .filter((shelf) => shelf.items.length > 0);
}
