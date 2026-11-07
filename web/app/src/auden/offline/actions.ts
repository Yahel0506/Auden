import type { Track } from '@/data/music/types';
import { isSyntheticId, sourceOf } from '@/playback/source';
import { getOfflineRuntime, keepStorage, songOf } from './runtime';

/** Whether a track is a real YouTube song that has audio to keep (not a file, a clip, or an unmatched Spotify row). */
export function canKeepOffline(track: Track): boolean {
  return sourceOf(track) === 'youtube' && !isSyntheticId(track.videoId) && !track.unavailable;
}

/** Queues the songs for offline listening and asks the browser to keep the storage. */
export async function saveForOffline(tracks: readonly Track[]): Promise<{ added: number; skipped: number }> {
  const songs = tracks.filter(canKeepOffline).map(songOf);
  const rt = getOfflineRuntime();
  const unusable = tracks.length - songs.length;
  if (!rt.supported || !songs.length) return { added: 0, skipped: tracks.length };
  void keepStorage();
  const result = await rt.queue.enqueue(songs);
  return { added: result.added, skipped: result.skipped + unusable };
}

export async function removeOfflineCopies(videoIds: string[]): Promise<void> {
  await getOfflineRuntime().queue.removeItems(videoIds);
}
