import type { Track } from '@/data/music/types';
import { onManualMatch } from '@/data/spotify/matches';
import { notifyStores } from '../db/notify';
import { openAudenDb } from '../db/schema';
import { ensureTrackRow } from './store';

/**
 * The listener fixed a Spotify song's YouTube Music match by hand: every saved copy that holds the wrong one for that
 * Spotify id now holds the right one, in the same place. If the copy already has the right song, the wrong entry goes
 * instead. Only entries a sync put there (they carry `fromSpotifyId`) are touched; a song added by hand is theirs.
 */
export async function replaceSpotifyMatch(spotifyId: string, track: Track): Promise<number> {
  let rowId: number;
  try {
    rowId = await ensureTrackRow(track);
  } catch {
    return 0; // a track that can't be stored (synthetic id) is not a replacement
  }
  const db = await openAudenDb();
  const tx = db.transaction('playlist_entry', 'readwrite');
  const store = tx.objectStore('playlist_entry');
  const all = await store.getAll();
  let changed = 0;
  for (const entry of all) {
    if (entry.fromSpotifyId !== spotifyId || entry.trackId === rowId) continue;
    const already = all.some((e) => e.playlistId === entry.playlistId && e.trackId === rowId);
    if (already) await store.delete(entry.entryId!);
    else await store.put({ ...entry, trackId: rowId });
    changed++;
  }
  await tx.done;
  if (changed) notifyStores('playlist_entry', 'playlist');
  return changed;
}

/** Applies manual matches to saved copies as they happen. Returns the cleanup. */
export function startManualMatchSync(): () => void {
  return onManualMatch((spotifyId, track) => void replaceSpotifyMatch(spotifyId, track).catch(() => undefined));
}
