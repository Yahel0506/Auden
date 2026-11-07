import { artKeyFromUrl } from '../../library/scan/diff';
import { useLocalArt } from '../../library/ui/data';
import type { PlaylistSummary } from '../store';

/** What to show as a playlist's cover: its stored mosaic or cloud cover, else the first song's own artwork. */
export function useCoverOf(summary: Pick<PlaylistSummary, 'playlist' | 'thumbnails'> | null | undefined): string | null {
  const art = useLocalArt();
  if (!summary) return null;
  const { coverUrl } = summary.playlist;
  const own = coverUrl ? (artKeyFromUrl(coverUrl) ? (art.get(artKeyFromUrl(coverUrl)!) ?? null) : coverUrl) : null;
  if (own) return own;
  const first = summary.thumbnails.find(Boolean);
  if (!first) return null;
  const key = artKeyFromUrl(first);
  return key ? (art.get(key) ?? null) : first;
}
