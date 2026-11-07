import { tracks } from '../db/dao';
import { localRowIdOf } from '@/playback/source';

/**
 * The key a song's saved lyrics live under: its video id, or for a file on this device its
 * `localUri` when it is linked to no YouTube song (as Android keys them by the file's uri). A library row's number changes when the
 * library is reindexed or restored, so keying by it would orphan the lyrics; the uri does not.
 */
export async function lyricsKeyFor(videoId: string): Promise<string> {
  const rowId = localRowIdOf(videoId);
  if (rowId === null) return videoId;
  const row = await tracks.get(Number(rowId)).catch(() => undefined);
  // As on Android (a library row's id is its video id, else its uri): a file linked to a YouTube song
  // shares that song's lyrics.
  return row?.videoId ?? row?.localUri ?? videoId;
}
