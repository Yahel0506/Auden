import { useMemo } from 'react';
import { useQuery } from '@tanstack/react-query';
import { create } from 'zustand';
import { usePlayer } from '@/state/player';
import { queryClient } from '@/lib/queryClient';
import { onGranted } from './http';
import { forgetLyrics, lookupLyrics, type LyricsResult, type SourceState } from './repository';
import { useLyricsSettings } from './settings';
import type { LyricsSourceId } from './sources';
import { audenKey } from '@/auden/db/queries';
import { lyricsKeyFor } from '@/auden/lyrics/key';
import { fromStoredProvider } from '@/auden/lyrics/names';
import { listVariants, readPref } from '@/auden/lyrics/store';
import { variantToResult } from '@/auden/lyrics/variant';

/** Per track: the provider the user picked in the panel, and how each provider's search went. */
interface LyricsUi {
  picked: Record<string, LyricsSourceId>;
  states: Record<string, Partial<Record<LyricsSourceId, SourceState>>>;
  pick: (videoId: string, source: LyricsSourceId | null) => void;
}

export const useLyricsUi = create<LyricsUi>()((set) => ({
  picked: {},
  states: {},
  pick: (videoId, source) =>
    set((s) => {
      const picked = { ...s.picked };
      if (source) picked[videoId] = source;
      else delete picked[videoId];
      return { picked };
    }),
}));

// A host the extension was refused for has been allowed: ask again.
onGranted(() => {
  forgetLyrics();
  void queryClient.invalidateQueries({ queryKey: ['lyrics'] });
});

function reportState(videoId: string, source: LyricsSourceId, state: SourceState) {
  useLyricsUi.setState((s) => ({ states: { ...s.states, [videoId]: { ...s.states[videoId], [source]: state } } }));
}

/** What the panel reads from the lyrics of the playing track (the parts of a query it uses). */
export interface LyricsState {
  data: LyricsResult | null | undefined;
  isPending: boolean;
  isFetching: boolean;
  fetchStatus: 'idle' | 'fetching' | 'paused';
  /** The key the song's saved lyrics and offset live under; null until known (or for a DJ line). */
  songKey: string | null;
  /** True when [data] was read from the song's saved lyrics, not asked of the providers. */
  saved: boolean;
  /** The provider chosen for this song (picked now, or remembered); null = "Best available". */
  chosen: LyricsSourceId | null;
}

/**
 * The playing track's lyrics. The song's saved variant comes first and needs no network: it is the one
 * chosen for the song, and only if it is still stored. Otherwise the providers are asked: the one
 * picked now, else the one remembered for the song (falling back to the whole chain when it has
 * nothing, so a dead provider cannot leave a song without lyrics for good), else the chain.
 */
export function useLyrics(): LyricsState {
  const videoId = usePlayer((s) => s.nowPlaying?.videoId ?? null);
  const title = usePlayer((s) => s.nowPlaying?.title ?? '');
  const artist = usePlayer((s) => s.nowPlaying?.artist ?? '');
  const isClip = usePlayer((s) => s.nowPlaying?.source === 'clip');
  const picked = useLyricsUi((s) => (videoId ? s.picked[videoId] : undefined));
  const order = useLyricsSettings((s) => s.order.filter((id) => !s.disabled.includes(id)).join(','));
  const preferSyllables = useLyricsSettings((s) => s.preferSyllables);

  const wantsLyrics = !!videoId && !!title && !isClip;
  const keyQuery = useQuery({
    queryKey: ['auden', 'lyrics-key', videoId],
    queryFn: () => lyricsKeyFor(videoId!),
    enabled: wantsLyrics,
    staleTime: Infinity,
  });
  const songKey = keyQuery.data ?? null;
  // Two queries, one per store: a write to either invalidates its own (startAudenQuerySync).
  const pref = useQuery({ queryKey: audenKey('song_lyrics_pref', songKey), queryFn: () => readPref(songKey!), enabled: !!songKey, staleTime: Infinity, retry: false });
  const variants = useQuery({ queryKey: audenKey('lyrics_variant', songKey), queryFn: () => listVariants(songKey!), enabled: !!songKey, staleTime: Infinity, retry: false });
  // A database that cannot be read must not keep the lyrics from loading: settled means answered or failed.
  const dbSettled = keyQuery.isError || (!!songKey && (pref.isFetched || pref.isError) && (variants.isFetched || variants.isError));

  const remembered = pref.data?.activeProvider ?? null;
  const stored = useMemo(() => {
    const row = remembered ? variants.data?.find((v) => v.provider === remembered) : undefined;
    return row ? variantToResult(row) : null;
  }, [remembered, variants.data]);
  const rememberedId = remembered ? fromStoredProvider(remembered) : null;
  const only = picked ?? (!stored && rememberedId ? rememberedId : undefined);
  const chosen = picked ?? rememberedId;

  const network = useQuery<LyricsResult | null>({
    queryKey: ['lyrics', videoId, title, artist, only ?? null, order, preferSyllables],
    // A DJ line has no lyrics; a saved variant needs no providers; and not before the database has answered,
    // or the network's answer would paint first and then switch to the saved one.
    enabled: wantsLyrics && dbSettled && !stored,
    staleTime: Infinity,
    gcTime: 30 * 60_000,
    retry: false,
    queryFn: async () => {
      const player = usePlayer.getState();
      const entry = player.queue[player.index];
      const durationSec = entry?.track.durationSec || player.nowPlaying?.durationSec || 0;
      const track = {
        videoId: videoId!,
        title,
        artist,
        durationMs: Math.round(durationSec * 1000),
        album: entry?.track.album?.name ?? null,
      };
      const onSource = (source: LyricsSourceId, state: SourceState) => reportState(videoId!, source, state);
      const found = await lookupLyrics(track, { ...(only && { only }), onSource });
      // A provider remembered from an earlier visit that has nothing now: the whole chain, not nothing.
      // (One picked this very session keeps its "Not on X", which is what the user asked to see.)
      if (!found && only && !picked) return lookupLyrics(track, { onSource });
      return found;
    },
  });

  if (stored) return { data: stored, isPending: false, isFetching: false, fetchStatus: 'idle', songKey, saved: true, chosen };
  if (wantsLyrics && !dbSettled) return { data: undefined, isPending: true, isFetching: true, fetchStatus: 'fetching', songKey, saved: false, chosen };
  return {
    data: network.data,
    isPending: network.isPending,
    isFetching: network.isFetching,
    fetchStatus: network.fetchStatus,
    songKey,
    saved: false,
    chosen,
  };
}
