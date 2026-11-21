import { t as translateUi } from '@/i18n';
import { PageHeader } from '@/components/PageHeader';
import { SquareLockup } from '@/components/music/Lockups';
import { SpotifyOpenCard } from '@/components/music/SpotifyOpenCard';
import { SpotifyProblem } from '@/components/music/SpotifyProblem';
import { EmptyState, NeedsExtension, stateStyles } from '@/components/music/States';
import type { SpotifyPlaylist } from '@bitchord/shared/bridge';
import { useEffect, useState } from 'react';
import type { MusicItem } from '@/data/music/types';
import { needsSignIn, spotifyFailureText, toSpotifyFailure } from '@/data/spotify/failure';
import { readLibraryCache } from '@/data/spotify/libraryCache';
import { spotifyAlbumHref, spotifyArtistHref } from '@/data/spotify/open';
import { useSpotifyAlbums, useSpotifyArtists, useSpotifyPlaylists } from '@/data/spotify/queries';
import { useExtension } from '@/extension/useExtension';
import { isTransientProblem, openSpotifySignIn, supportsSpotifyLibrary, useRefreshSpotifyAuth, useSetSpotifyConnected, useSpotifyAuth } from '@/extension/useSpotifyAuth';
import { PlainSkeleton } from '@/skeletons/PageSkeletons';
import { seeAllGrid } from './pageStyles';

/**
 * The signed-in Spotify account's playlists (SpotifyLibraryScreen): this page
 * is only the way in; a playlist opens as an ordinary playlist page whose
 * songs are matched to YouTube Music.
 */
export default function SpotifyPage() {
  const extension = useExtension();
  const auth = useSpotifyAuth();
  const playlists = useSpotifyPlaylists();
  const connect = useSetSpotifyConnected();
  const retry = useRefreshSpotifyAuth();
  const [tab, setTab] = useState<'playlists' | 'albums' | 'artists'>('playlists');
  const hasLibrary = extension.state === 'present' && supportsSpotifyLibrary(extension.extension);

  if (extension.state === 'missing') return <NeedsExtension what="your Spotify playlists" />;
  if (auth.state === 'checking') return <PlainSkeleton />;
  if (auth.state === 'unavailable') {
    return (
      <>
        <PageHeader title="Spotify" />
        <EmptyState title={translateUi("spotify_page.update_the_extension")} detail={translateUi("spotify_page.this_version_of_the_auden")} />
      </>
    );
  }
  const state = auth.auth;
  useEffect(() => {
    if (!state.signedIn && state.problem) console.error('Auden Spotify account status', state.problem);
  }, [state.problem, state.signedIn]);
  // A throttle or a dropped connection on a cold start, with lists saved: show them, and say what happened.
  const offlineWithCache = !state.signedIn && isTransientProblem(state) && !!readLibraryCache();
  if (!state.signedIn && !offlineWithCache) {
    const [title, detail, action] = !state.browserSignedIn
      ? [translateUi('spotify_page.sign_in_to_spotify'), translateUi('spotify_page.spotify_account_through_extension'), translateUi('spotify_page.sign_in')]
      : !state.connected
        ? [translateUi('spotify_page.spotify_is_disconnected'), translateUi('spotify_page.connect_to_see_playlists'), translateUi('spotify_page.connect')]
        : [translateUi('spotify_page.couldnt_read_account'), translateUi('spotify_page.spotify_didnt_answer'), translateUi('spotify_page.try_again')];
    return (
      <>
        <PageHeader title="Spotify" />
        <div className={stateStyles.state}>
          <p className={stateStyles.title}>{title}</p>
          <p className={stateStyles.detail}>{detail}</p>
          <button
            className={stateStyles.button}
            disabled={connect.isPending || retry.isPending}
            onClick={() =>
              !state.browserSignedIn ? openSpotifySignIn() : !state.connected ? connect.mutate(true) : retry.mutate()
            }
          >
            {action}
          </button>
        </div>
      </>
    );
  }
  return (
    <>
      <PageHeader title="Spotify" />
      {(offlineWithCache || (state.signedIn && state.problemKind && isTransientProblem(state))) && (
        <p className="mx-[var(--page-gutter,20px)] mb-3 flex items-center gap-3 type-callout text-secondary">
          <span>{translateUi('spotify_page.showing_cached_library', { reason: spotifyFailureText(state.problemKind ?? 'network') })}</span>
          <button className="type-callout-emphasized text-key" disabled={retry.isPending} onClick={() => retry.mutate()}>{translateUi("spotify_page.retry")}</button>
        </p>
      )}
      <div className="flex gap-1 px-[var(--page-gutter,20px)] pb-2" role="tablist" aria-label={translateUi("spotify_page.spotify_library")}>
        {(['playlists', 'albums', 'artists'] as const).map((id) => (
          <button
            key={id}
            role="tab"
            aria-selected={tab === id}
            className={`rounded-pill px-3.5 py-1.5 type-callout-emphasized ${tab === id ? 'bg-pill-bg text-pill' : 'text-secondary hover:bg-quinary'}`}
            onClick={() => setTab(id)}
          >
            {translateUi(`spotify_page.tab_${id}` as keyof typeof import('@/i18n.en.json'))}
          </button>
        ))}
      </div>
      {tab === 'playlists' && <PlaylistsTab query={playlists} />}
      {tab === 'albums' && (hasLibrary ? <AlbumsTab /> : <EmptyState title={translateUi("spotify_page.update_the_extension")} detail={translateUi("spotify_page.this_version_of_the_auden_2")} />)}
      {tab === 'artists' && (hasLibrary ? <ArtistsTab /> : <EmptyState title={translateUi("spotify_page.update_the_extension")} detail={translateUi("spotify_page.this_version_of_the_auden_3")} />)}
    </>
  );
}

type ListQuery<T> = { data: T[] | undefined; isError: boolean; error: unknown; refetch: () => unknown; isFetching: boolean };

/** A list's loading, error and empty states, shared by the three tabs; a cached copy shows while it refreshes. */
function LibraryList<T>({ query, empty, children }: { query: ListQuery<T>; empty: string; children: (items: T[]) => React.ReactNode }) {
  if (query.isError && !query.data) return <SpotifyProblem error={query.error} onRetry={() => void query.refetch()} />;
  if (!query.data) return <PlainSkeleton />;
  return (
    <>
      {query.isError && <SpotifyRefreshError error={query.error} onRetry={() => void query.refetch()} />}
      {!query.data.length && <EmptyState title={empty} />}
      <ul className={seeAllGrid}>{children(query.data)}</ul>
    </>
  );
}

/** The saved copy stays up when a refresh fails; say so, and offer the retry. */
function SpotifyRefreshError({ error, onRetry }: { error: unknown; onRetry: () => void }) {
  const failure = toSpotifyFailure(error);
  return (
    <p className="mx-[var(--page-gutter,20px)] mb-3 flex items-center gap-3 type-callout text-secondary">
      <span>{spotifyFailureText(failure.kind)} Showing what was saved.</span>
      <button className="type-callout-emphasized text-key" onClick={needsSignIn(failure.kind) ? openSpotifySignIn : onRetry}>
        {needsSignIn(failure.kind) ? translateUi("pages_spotifypage_sign_in") : 'Retry'}
      </button>
    </p>
  );
}

function PlaylistsTab({ query }: { query: ListQuery<SpotifyPlaylist> }) {
  return (
    <LibraryList query={query} empty={translateUi('spotify_page.no_playlists_yet')}>
      {(playlists) =>
        playlists.map((p) => {
          const item: MusicItem = {
            kind: 'spotify-playlist',
            id: p.id,
            title: p.name,
            subtitle: p.owner ?? 'Spotify',
            artists: [],
            album: null,
            thumbnail: p.imageUrl,
            wide: false,
            durationSec: null,
            explicit: false,
          };
          return (
            <li key={item.id}>
              <SquareLockup item={item} />
            </li>
          );
        })
      }
    </LibraryList>
  );
}

function AlbumsTab() {
  const albums = useSpotifyAlbums();
  return (
    <LibraryList query={albums} empty={translateUi('spotify_page.no_saved_albums_yet')}>
      {(items) =>
        items.map((a) => (
          <li key={a.id}>
            <SpotifyOpenCard title={a.name} subtitle={a.artist ?? 'Album'} imageUrl={a.imageUrl} findHref={() => spotifyAlbumHref(a.name, a.artist)} />
          </li>
        ))
      }
    </LibraryList>
  );
}

function ArtistsTab() {
  const artists = useSpotifyArtists();
  return (
    <LibraryList query={artists} empty={translateUi('spotify_page.no_followed_artists_yet')}>
      {(items) =>
        items.map((a) => (
          <li key={a.id}>
            <SpotifyOpenCard title={a.name} subtitle={translateUi("spotify_page.artist")} imageUrl={a.imageUrl} round findHref={() => spotifyArtistHref(a.name)} />
          </li>
        ))
      }
    </LibraryList>
  );
}
