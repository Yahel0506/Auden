import { useMemo } from 'react';
import { useQuery } from '@tanstack/react-query';
import type { Track } from '@/data/music/types';
import { localTrackId } from '@/playback/source';
import { audenKey } from '../../db/queries';
import { tracks } from '../../db/dao';
import { openAudenDb } from '../../db/schema';
import { localArt, parseLocalUri } from '../../db/libraryDao';
import { useRoots } from '../roots';
import type { TrackRow } from '../../db/schema';
import type { LocalSong } from '../groups';
import { splitArtists } from '../artistSplit';
import { artKeyFromUrl } from '../scan/diff';

/** A row of the local library as the player's `Track`: its own synthetic id, `source: 'local'`. */
export function trackOfRow(row: TrackRow, art: ReadonlyMap<string, string>): Track {
  const key = artKeyFromUrl(row.thumbnailUrl);
  return {
    videoId: localTrackId(row.id!),
    source: 'local',
    title: row.title,
    artists: splitArtists(row.artist).map((name) => ({ name, id: null })),
    album: row.album ? { name: row.album, id: null } : null,
    durationSec: row.durationMs ? row.durationMs / 1000 : null,
    explicit: false,
    thumbnail: (key && art.get(key)) || null,
    index: null,
    plays: null,
  };
}

export type LocalItem = LocalSong & { row: TrackRow; track: Track };

/**
 * Cover object URLs, one per cover key for the life of the page: a cover is ≤256 px (a few KB), the number of
 * albums bounds the cache, and a URL is only replaced when its bytes change. Media Session and the player
 * read these URLs too, so they must not be revoked while a track using one may be playing.
 */
const urls = new Map<string, { url: string; size: number }>();

/** The same cover URLs, outside React (the DJ builds its pool in an event handler). */
export async function localArtUrls(): Promise<ReadonlyMap<string, string>> {
  for (const row of await localArt.all()) {
    const have = urls.get(row.key);
    if (have && have.size === row.bytes.byteLength) continue;
    urls.set(row.key, { url: URL.createObjectURL(new Blob([row.bytes], { type: row.mime })), size: row.bytes.byteLength });
  }
  return new Map([...urls].map(([key, v]) => [key, v.url] as const));
}

export function useLocalArt(): ReadonlyMap<string, string> {
  const query = useQuery({
    queryKey: audenKey('local_art', 'urls'),
    queryFn: async () => {
      for (const row of await localArt.all()) {
        const have = urls.get(row.key);
        if (have && have.size === row.bytes.byteLength) continue;
        urls.set(row.key, { url: URL.createObjectURL(new Blob([row.bytes], { type: row.mime })), size: row.bytes.byteLength });
      }
      return new Map([...urls].map(([key, v]) => [key, v.url] as const));
    },
    staleTime: Infinity,
  });
  return query.data ?? EMPTY;
}
const EMPTY: ReadonlyMap<string, string> = new Map();

/** Every indexed file, as songs. */
export function useLocalItems(): { items: LocalItem[]; loading: boolean } {
  const art = useLocalArt();
  const query = useQuery({
    queryKey: audenKey('track', 'local'),
    queryFn: async () => (await (await openAudenDb()).getAllFromIndex('track', 'localUri')).filter((r) => r.id != null),
    staleTime: Infinity,
  });
  const rows = query.data;
  const roots = useRoots((st) => st.roots);
  const items = useMemo(() => (rows ?? []).map<LocalItem>((row) => ({
    id: row.id!,
    title: row.title,
    artist: row.artist,
    album: row.album,
    albumArtist: row.albumArtist,
    genre: row.genre,
    // Shown as "<folder you added>/<subfolder>", so files at the top of a folder still have a place in Folders.
    folder: folderLabel(row, roots),
    trackNumber: row.trackNumber,
    discNumber: row.discNumber,
    year: row.year,
    durationMs: row.durationMs,
    row,
    track: trackOfRow(row, art),
  })), [rows, art, roots]);
  return { items, loading: query.isPending };
}

export function useReviewRows() {
  return useQuery({
    queryKey: audenKey('track', 'review'),
    queryFn: async () => (await tracks.all()).filter((r) => r.localUri && r.identStatus === 'REVIEW' && r.suggestedVideoId),
    staleTime: Infinity,
  });
}

function folderLabel(row: TrackRow, roots: readonly { id: string; name: string }[]): string {
  const rootId = row.localUri ? parseLocalUri(row.localUri)?.rootId : null;
  const root = roots.find((r) => r.id === rootId)?.name ?? 'Music';
  return row.folder ? `${root}/${row.folder}` : root;
}
