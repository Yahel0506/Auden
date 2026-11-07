import { newTrack } from '../../db/dao';
import { localUriOf } from '../../db/libraryDao';
import { TrackOrigin, type TrackRow } from '../../db/schema';
import { extractYoutubeId } from '../youtubeIdInName';
import { artUrlOf, folderOf } from './diff';
import type { ScanEntry } from './protocol';

export { artKeyOf, folderOf } from './diff';

export const UNKNOWN_ARTIST = 'Unknown Artist';

/** "Artist - Title [id].m4a" → "Artist - Title": a file's name without extension or the YouTube id suffix. */
export function titleFromFileName(relPath: string): string {
  const name = relPath.slice(relPath.lastIndexOf('/') + 1);
  const dot = name.lastIndexOf('.');
  const base = (dot > 0 ? name.slice(0, dot) : name).replace(/\s*\[[A-Za-z0-9_-]{11}\]\s*$/, '');
  return base.trim() || name;
}

/**
 * The row for a scanned file. A changed file keeps what the index learned about it (id, when it was added,
 * its YouTube id and identification) so `auden:local:<id>` stays stable and a re-index never forgets a match.
 */
export function rowFromEntry(rootId: string, entry: ScanEntry, existing?: TrackRow): TrackRow {
  const tags = entry.tags;
  const localUri = localUriOf(rootId, entry.relPath);
  const durationMs = tags?.durationSec ? Math.round(tags.durationSec * 1000) : null;
  const fresh = newTrack({
    title: tags?.title?.trim() || titleFromFileName(entry.relPath),
    artist: tags?.artist?.trim() || UNKNOWN_ARTIST,
    localUri,
    path: entry.relPath,
    album: tags?.album?.trim() || null,
    albumArtist: tags?.albumArtist?.trim() || null,
    genre: tags?.genre?.trim() || null,
    year: tags?.year && tags.year > 0 ? tags.year : null,
    trackNumber: tags?.trackNumber && tags.trackNumber > 0 ? tags.trackNumber : null,
    discNumber: tags?.discNumber && tags.discNumber > 0 ? tags.discNumber : null,
    durationMs,
    durationText: durationMs ? formatDuration(durationMs / 1000) : null,
    folder: folderOf(entry.relPath),
    sizeBytes: entry.size,
    modifiedAt: entry.modifiedAt,
    thumbnailUrl: entry.artKey ? artUrlOf(entry.artKey) : null,
    origin: TrackOrigin.LOCAL,
  });
  if (!existing) return fresh;
  return {
    ...fresh,
    id: existing.id,
    addedAt: existing.addedAt,
    videoId: existing.videoId,
    spotifyId: existing.spotifyId,
    isrc: existing.isrc,
    artistId: existing.artistId,
    albumId: existing.albumId,
    origin: existing.origin,
    identStatus: existing.identStatus,
    identSource: existing.identSource,
    suggestedVideoId: existing.suggestedVideoId,
    suggestedTitle: existing.suggestedTitle,
    suggestedArtist: existing.suggestedArtist,
    identifiedAt: existing.identifiedAt,
  };
}

export function formatDuration(sec: number): string {
  const s = Math.round(sec);
  const h = Math.floor(s / 3600);
  const m = Math.floor((s % 3600) / 60);
  const r = s % 60;
  return h > 0 ? `${h}:${String(m).padStart(2, '0')}:${String(r).padStart(2, '0')}` : `${m}:${String(r).padStart(2, '0')}`;
}

/** A YouTube id written into the file's name settles identification before any search (Android: stage 1). */
export function idFromEntry(entry: ScanEntry): string | null {
  return extractYoutubeId(entry.relPath.slice(entry.relPath.lastIndexOf('/') + 1));
}
