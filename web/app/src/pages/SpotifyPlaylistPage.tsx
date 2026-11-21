import { number, t as translateUi } from '@/i18n';
import { SaveToDeviceButton } from '@/auden/playlists/ui/SaveToDeviceButton';
import { saveSpotifyToDevice, saveYouTubeToDevice } from '@/auden/playlists/actions';
import { useMutation } from '@tanstack/react-query';
import { ListPlus } from 'lucide-react';
import { useState } from 'react';
import { useNavigate, useParams } from 'react-router';
import { DetailHeader, detailButton } from '@/components/music/DetailHeader';
import { playList, shuffleList } from '@/components/music/play';
import { SpotifyProblem } from '@/components/music/SpotifyProblem';
import { EmptyState, NeedsExtension } from '@/components/music/States';
import { TrackList } from '@/components/music/TrackList';
import { createPlaylist } from '@/data/music/api';
import { useSignedIn } from '@/data/music/queries';
import type { HeaderInfo } from '@/data/music/types';
import { useSpotifyPlaylistInfo, useSpotifyRows } from '@/data/spotify/queries';
import { useExtension } from '@/extension/useExtension';
import { useSpotifySignedIn } from '@/extension/useSpotifyAuth';
import { useArtworkColor } from '@/lib/artworkColor';
import { usePageTint } from '@/lib/usePageTint';
import { PlaylistSkeleton } from '@/skeletons/PageSkeletons';
import { detailFooter } from './pageStyles';

/**
 * A Spotify playlist as an ordinary playlist page (MainViewModel.openSpotifyPage):
 * Spotify's rows go up at once, each swapped for its YouTube Music song as the
 * match arrives. Import keeps the result as a YouTube Music playlist
 * (SpotifyImportAlert), with the songs that found nothing left out and named.
 */
export default function SpotifyPlaylistPage() {
  const id = decodeURIComponent(useParams().id ?? '');
  const extension = useExtension();
  const signedIn = useSpotifySignedIn();
  const info = useSpotifyPlaylistInfo(id);
  const rows = useSpotifyRows(id);
  usePageTint(useArtworkColor(info.data?.imageUrl));

  if (extension.state === 'missing') return <NeedsExtension what="your Spotify playlists" />;
  if (!signedIn) return <EmptyState title={translateUi("spotify_playlist_page.sign_in_to_spotify")} detail={translateUi("spotify_playlist_page.open_spotify_in_the_sidebar")} />;
  if (rows.status === 'error' && !rows.rows.length) return <SpotifyProblem error={rows.error} onRetry={rows.retry} />;
  if (!info.data || (rows.status === 'loading' && !rows.rows.length)) return <PlaylistSkeleton />;

  const playable = rows.rows.filter((t) => !t.unavailable);
  const missing = rows.rows.filter((t) => t.unavailable === 'missing');
  const total = Math.max(rows.total, info.data.total);
  const meta = [
    translateUi('spotify_playlist_page.song_count', { count: total }),
    rows.matching > 0 ? translateUi('spotify_playlist_page.finding_matches', { loaded: rows.rows.length - rows.matching, total: rows.rows.length }) : null,
    rows.matching === 0 && missing.length > 0 ? translateUi('spotify_playlist_page.not_on_youtube', { count: missing.length }) : null,
  ]
    .filter(Boolean)
    .join(' • ');
  const headerInfo: HeaderInfo = {
    title: info.data.name,
    subtitle: translateUi('spotify_playlist_page.spotify_playlist'),
    meta,
    description: null,
    thumbnail: info.data.imageUrl,
    artists: info.data.owner ? [{ name: info.data.owner, id: null }] : [],
    explicit: false,
    library: null,
  };
  const context = { source: { title: info.data.name, type: 'playlist' as const, id: `spotify:${id}` } };

  return (
    <>
      <DetailHeader
        info={headerInfo}
        canPlay={playable.length > 0}
        onPlay={() => playList(playable, 0, context)}
        onShuffle={() => shuffleList(playable, context)}
        extra={
          <>
            <SaveToDeviceButton onSave={() => saveSpotifyToDevice(id, info.data.name, info.data.imageUrl)} />
            <ImportButton title={info.data.name} videoIds={playable.map((t) => t.videoId)} ready={rows.matching === 0} />
          </>
        }
      />
      {rows.partial && (
        <p className="mx-[var(--page-gutter,20px)] my-3 flex items-center gap-3 type-callout text-secondary">
          <span>{translateUi('spotify_playlist_page.partial_playlist', { loaded: number(rows.rows.length), total: number(total) })}</span>
          <button className="type-callout-emphasized text-key" onClick={rows.retry}>{translateUi("spotify_playlist_page.load_the_rest")}</button>
        </p>
      )}
      <TrackList tracks={rows.rows} variant="playlist" source={context.source} />
      <footer className={detailFooter}>
        <p>{translateUi("spotify_playlist_page.from_spotify_songs_are_matched")}</p>
      </footer>
    </>
  );
}

/** Keeps the matched songs as a private YouTube Music playlist; asks once before writing to the account. */
function ImportButton({ title, videoIds, ready }: { title: string; videoIds: string[]; ready: boolean }) {
  const ytSignedIn = useSignedIn();
  const navigate = useNavigate();
  const [confirming, setConfirming] = useState(false);
  const create = useMutation({
    mutationFn: () => createPlaylist(title, videoIds, translateUi('spotify_playlist_page.imported_from_spotify_with_auden')),
    onSuccess: (playlistId) => navigate(`/playlist/${encodeURIComponent(`VL${playlistId}`)}`),
  });
  if (!ytSignedIn) return null;
  const label = !ready
    ? translateUi('spotify_playlist_page.import_to_youtube_matching')
    : create.isPending
      ? translateUi('spotify_playlist_page.importing')
      : create.isError
        ? translateUi('spotify_playlist_page.import_failed_try_again')
        : confirming
          ? translateUi('spotify_playlist_page.confirm_private_playlist', { count: number(videoIds.length) })
          : translateUi('spotify_playlist_page.import_to_youtube_music');
  return (
    <button
      className={confirming ? detailButton.pill : detailButton.circle}
      aria-label={label}
      title={label}
      disabled={!ready || !videoIds.length || create.isPending}
      onClick={() => (confirming ? (setConfirming(false), create.mutate()) : setConfirming(true))}
      onBlur={() => setConfirming(false)}
    >
      <ListPlus size={18} strokeWidth={2.2} />
      {confirming && <span>{translateUi("spotify_playlist_page.make_playlist")}</span>}
    </button>
  );
}
