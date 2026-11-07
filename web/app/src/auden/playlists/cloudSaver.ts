import type { Track } from '@/data/music/types';
import { EmptySourceError, saveFromSource, type SaveResult } from './store';

/**
 * Saving a YouTube Music or Spotify list as a copy on this device (Android CloudSaver), and re-saving it later (the
 * sync): the same call, because a re-save finds the copy by its source id. Nothing here ever writes to either service.
 */

export const SPOTIFY_SOURCE = 'spotify:playlist:';

/** The copy's key: a browse id without YouTube's "VL" prefix, or `spotify:playlist:<id>`. */
export const youtubeSourceId = (browseId: string): string => browseId.replace(/^VL/, '');
export const spotifySourceId = (playlistId: string): string => `${SPOTIFY_SOURCE}${playlistId}`;
export const isSpotifySource = (sourceId: string): boolean => sourceId.startsWith(SPOTIFY_SOURCE);
const isAlbumId = (id: string): boolean => id.startsWith('MPREb') || id.startsWith('OLAK');

export interface CloudDeps<S extends { id: string } = { id: string }> {
  /** A playlist's songs (all pages) with its title and cover; an album's too. */
  youtube: (browseId: string) => Promise<{ title: string; thumbnail: string | null; tracks: Track[] }>;
  spotify: {
    /** Every track of the playlist, how many the original lists, and whether the read reached the end. */
    tracks: (playlistId: string) => Promise<{ items: S[]; complete: boolean; cover?: string | null }>;
    /** The YouTube Music song for one Spotify track; null when none. */
    match: (item: S, index: number) => Promise<Track | null>;
  };
}

export type SaveOutcome = (SaveResult & { unmatched: number }) | { failed: string };

/** Runs up to [limit] jobs at once, results in input order. */
async function mapLimit<T, R>(items: readonly T[], limit: number, fn: (item: T, i: number) => Promise<R>): Promise<R[]> {
  const out: R[] = new Array(items.length);
  let next = 0;
  await Promise.all(
    Array.from({ length: Math.min(limit, items.length) }, async () => {
      while (next < items.length) {
        const i = next++;
        out[i] = await fn(items[i]!, i);
      }
    }),
  );
  return out;
}

/** Saves (or re-saves) a YouTube Music playlist or album. Add-only on re-save. */
export async function saveYouTubeList(deps: Pick<CloudDeps, 'youtube'>, browseId: string, hint?: { title?: string; thumbnail?: string | null }): Promise<SaveOutcome> {
  try {
    const list = await deps.youtube(browseId);
    const album = isAlbumId(youtubeSourceId(browseId));
    const tracks = list.tracks.map((t) => ({ ...t, album: t.album ?? (album ? { name: list.title, id: null } : null) }));
    const r = await saveFromSource({
      sourceId: youtubeSourceId(browseId),
      title: hint?.title || list.title,
      tracks,
      coverUrl: list.thumbnail ?? hint?.thumbnail ?? null,
    });
    return { ...r, unmatched: 0 };
  } catch (error) {
    return { failed: error instanceof EmptySourceError ? 'There was nothing to save.' : error instanceof Error ? error.message : String(error) };
  }
}

/**
 * Saves (or re-saves) a Spotify playlist: every track is matched to YouTube Music (4 at a time). The re-save removes
 * songs the playlist no longer lists, but only after a complete read with at least one match (see reconcile.ts).
 */
export async function saveSpotifyPlaylist<S extends { id: string }>(deps: CloudDeps<S>, playlistId: string, hint?: { title?: string; cover?: string | null }): Promise<SaveOutcome> {
  try {
    const source = await deps.spotify.tracks(playlistId);
    const matches = await mapLimit(source.items, 4, async (item, i) => ({ id: item.id, track: await deps.spotify.match(item, i).catch(() => null) }));
    const found = matches.filter((m): m is { id: string; track: Track } => m.track !== null);
    const r = await saveFromSource({
      sourceId: spotifySourceId(playlistId),
      title: hint?.title || 'Spotify Playlist',
      tracks: found.map((m) => m.track),
      spotifyIds: found.map((m) => m.id),
      listedSpotifyIds: source.items.map((i) => i.id),
      complete: source.complete,
      coverUrl: source.cover ?? hint?.cover ?? null,
    });
    return { ...r, unmatched: matches.length - found.length };
  } catch (error) {
    return { failed: error instanceof EmptySourceError ? 'None of the songs could be matched.' : error instanceof Error ? error.message : String(error) };
  }
}

/** Re-saves whichever copy this is, by its source id. */
export function resaveCopy<S extends { id: string }>(deps: CloudDeps<S>, sourceId: string, title?: string): Promise<SaveOutcome> {
  return isSpotifySource(sourceId)
    ? saveSpotifyPlaylist(deps, sourceId.slice(SPOTIFY_SOURCE.length), { title })
    : saveYouTubeList(deps, isAlbumId(sourceId) ? sourceId : `VL${sourceId}`, { title });
}
