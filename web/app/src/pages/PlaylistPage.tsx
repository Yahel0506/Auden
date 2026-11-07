import { OfflineButton } from '@/auden/offline/ui/OfflineButton';
import { SaveToDeviceButton } from '@/auden/playlists/ui/SaveToDeviceButton';
import { saveSpotifyToDevice, saveYouTubeToDevice } from '@/auden/playlists/actions';
import { useCallback } from 'react';
import { LyricsBatchButton } from '@/components/music/LyricsBatchButton';
import { useParams } from 'react-router';
import { DetailHeader } from '@/components/music/DetailHeader';
import { playList, shuffleList } from '@/components/music/play';
import { LoadError, LoadMoreError, NeedsExtension } from '@/components/music/States';
import { TrackList } from '@/components/music/TrackList';
import { usePlaylist, useYouTubeReady } from '@/data/music/queries';
import { useArtworkColor } from '@/lib/artworkColor';
import { usePageTint } from '@/lib/usePageTint';
import { PlaylistSkeleton } from '@/skeletons/PageSkeletons';
import { detailFooter } from './pageStyles';

/** A playlist (report §14): header, then the artwork track list, loading on as you scroll. */
export default function PlaylistPage() {
  const id = decodeURIComponent(useParams().id ?? '');
  const { missing } = useYouTubeReady();
  const playlist = usePlaylist(id);
  const header = playlist.data?.pages[0]?.header ?? null;
  usePageTint(useArtworkColor(header?.thumbnail));

  const { hasNextPage, isFetchingNextPage, isFetchNextPageError, fetchNextPage } = playlist;
  const onNearEnd = useCallback(() => {
    if (hasNextPage && !isFetchingNextPage && !isFetchNextPageError) void fetchNextPage();
  }, [hasNextPage, isFetchingNextPage, isFetchNextPageError, fetchNextPage]);

  if (missing) return <NeedsExtension />;
  if (playlist.isError && !playlist.data) return <LoadError error={playlist.error} onRetry={() => void playlist.refetch()} />;
  if (!playlist.data || !header) return <PlaylistSkeleton />;

  const tracks = playlist.data.pages.flatMap((page) => page.tracks);
  // A playlist loads as you scroll: whoever needs all of it reads the rest first.
  const loadEverything = async () => {
    let pages = playlist.data.pages;
    let more = hasNextPage;
    while (more) {
      const next = await fetchNextPage();
      if (next.isError || !next.data) break;
      pages = next.data.pages;
      more = !!next.hasNextPage;
    }
    return pages.flatMap((page) => page.tracks);
  };
  const context = { source: { title: header.title, type: 'playlist' as const, id } };
  return (
    <>
      <DetailHeader
        info={header}
        canPlay={tracks.length > 0}
        onPlay={() => playList(tracks, 0, context)}
        onShuffle={() => shuffleList(tracks, context)}
        extra={
          <>
          <SaveToDeviceButton onSave={() => saveYouTubeToDevice(id, header.title, header.thumbnail)} />
          {/* The pages loaded so far: a playlist streams in as you scroll. */}
          <OfflineButton tracks={tracks} collection={{ kind: 'yt', id, title: header.title }} loadAll={loadEverything} />
          <LyricsBatchButton
            tracks={tracks}
            title={header.title}
            // A playlist loads as you scroll: the run is for all of it, not the pages seen so far.
            loadAll={loadEverything}
          />
          </>
        }
      />
      <TrackList tracks={tracks} variant="playlist" onNearEnd={onNearEnd} source={context.source} />
      {isFetchingNextPage && <div className="flex justify-center py-5 type-callout text-secondary">Loading more songs…</div>}
      {playlist.isFetchNextPageError && <LoadMoreError onRetry={() => void fetchNextPage()} />}
      <footer className={detailFooter}>
        <p>{header.meta}</p>
      </footer>
    </>
  );
}
