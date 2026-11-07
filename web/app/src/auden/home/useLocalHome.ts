import { hasOfflineCopy } from '../offline/kept';
import { keepPreviousData, useQuery, useQueryClient } from '@tanstack/react-query';
import { useYouTubeReady } from '@/data/music/queries';
import { useEffect } from 'react';
import { useOnline } from '@/lib/useOnline';
import { subscribeToStores } from '../db/notify';
import { audenKey } from '../db/queries';
import { openAudenDb } from '../db/schema';
import { hasLink, useLinkVersion } from '../library/linkIndex';
import { useLocalArt } from '../library/ui/data';
import { listPlaylists } from '../playlists/store';
import { listeningMonths } from '../stats/listening';
import { loadHomeInputs, resolveArt } from './data';
import { buildLocalShelves, textFor, type LocalShelf } from './shelves';
import type { Track } from '@/data/music/types';

export interface LocalHome {
  shelves: LocalShelf[];
  tracks: ReadonlyMap<string, Track>;
  loading: boolean;
}

const EMPTY: LocalHome = { shelves: [], tracks: new Map(), loading: true };
const FAILED: LocalHome = { shelves: [], tracks: new Map(), loading: false };
const lang = (): 'en' | 'es' => (typeof navigator !== 'undefined' && navigator.language?.toLowerCase().startsWith('es') ? 'es' : 'en');

/**
 * The listener's own shelves. Recomputed when Home opens and when the library, a playlist, the covers or the
 * link index change; NOT on every listening flush (the recorder writes about every 30 s while playing, and the
 * shelves would reshuffle under the cursor).
 */
export function useLocalHome(): LocalHome {
  const { missing } = useYouTubeReady();
  // A streamed song only plays with a network AND the extension (YouTube needs its request headers), so without either
  // the shelves keep only what plays from a file.
  const online = useOnline() && !missing;
  const art = useLocalArt();
  const linkVersion = useLinkVersion((s) => s.v);
  const queryClient = useQueryClient();

  useEffect(
    () =>
      subscribeToStores((stores) => {
        if (stores.some((s) => s === 'playlist' || s === 'playlist_entry')) void queryClient.invalidateQueries({ queryKey: audenKey('track', 'home') });
      }),
    [queryClient],
  );

  const query = useQuery({
    // Under the `track` key, so a scan or an identification (throttled) refreshes it.
    queryKey: audenKey('track', 'home', online, linkVersion, art.size),
    refetchOnMount: 'always',
    // A new key (covers loaded, link index rebuilt, network flipped) keeps showing the last shelves while it recomputes.
    placeholderData: keepPreviousData,
    staleTime: 0,
    queryFn: async (): Promise<LocalHome> => {
      const now = Date.now();
      const inputs = await loadHomeInputs(
        {
          months: listeningMonths,
          rows: async () => (await openAudenDb()).getAll('track'),
          playlists: async () =>
            (await listPlaylists()).map((s) => ({
              id: s.playlist.id,
              title: s.playlist.title,
              count: s.count,
              pinned: s.playlist.pinned,
              art: resolveArt(s.playlist.coverUrl ?? s.thumbnails.find(Boolean) ?? null, art),
            })),
          art,
          hasFile: (track) => hasLink(track) || hasOfflineCopy(track.videoId),
        },
        { now, online },
      );
      return { shelves: buildLocalShelves(inputs.played, inputs.library, inputs.playlists, now, textFor(lang())), tracks: inputs.tracks, loading: false };
    },
  });
  if (query.isError) {
    // The listener's own shelves failing (a database that won't open) must not take the YouTube feed down with them.
    console.error('Home: the local shelves failed', query.error);
    return FAILED;
  }
  return query.data ?? EMPTY;
}
