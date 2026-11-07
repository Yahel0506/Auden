import { useEffect, useRef } from 'react';
import { PageHeader } from '@/components/PageHeader';
import { ShelfView, TrackShelf } from '@/components/music/ShelfView';
import { EmptyState, LoadError, LoadMoreError, NeedsExtension } from '@/components/music/States';
import { useHome, useLibrary, useYouTubeReady } from '@/data/music/queries';
import type { Shelf, Track } from '@/data/music/types';
import { PrefetchLink } from '@/components/PrefetchLink';
import { compose, type HomeEntry } from '@/auden/home/composer';
import { useHomeSettings } from '@/auden/home/settings';
import { isRecentShelf, RECENT_TITLE, withoutVideos } from '@/auden/home/filter';
import { LOCAL_SHELF_KINDS } from '@/auden/home/shelves';
import { LocalShelfView } from '@/auden/home/ui/LocalShelfView';
import { useLocalHome } from '@/auden/home/useLocalHome';
import { useOnline } from '@/lib/useOnline';
import { DjCard } from '@/auden/dj/ui/DjCard';
import { HomeSkeleton } from '@/skeletons/PageSkeletons';
import { ShelfSkeleton } from '@/skeletons/ShelfSkeleton';

const RECENT_LIMIT = 20;
const RECENT_SOURCE = { title: RECENT_TITLE, type: 'home' as const };

/**
 * The account's listening history, newest first, one row per song
 * (YtMusicRepository.recentlyPlayed): the first 20, signed in only. The same
 * query as Library's Recently Played, so either page warms the other.
 */
function useRecentlyPlayed(): Track[] {
  const history = useLibrary('recent');
  const first = history.data?.pages[0]?.data.tracks ?? [];
  const seen = new Set<string>();
  return first.filter((t) => !seen.has(t.videoId) && seen.add(t.videoId)).slice(0, RECENT_LIMIT);
}

type Remote = { kind: 'recent'; tracks: Track[] } | { kind: 'feed'; shelf: Shelf };

/**
 * Home: the listener's own shelves (history, library, playlists) mixed with YouTube Music's feed by the weight in
 * Settings. With no extension or no network it is the listener's own; it is never empty. Recently Played is the
 * feed's first shelf, so a high weight can put the listener's own above it.
 */
export default function HomePage() {
  const { missing } = useYouTubeReady();
  const online = useOnline();
  const home = useHome();
  const recent = useRecentlyPlayed();
  const local = useLocalHome();
  const weight = useHomeSettings((s) => s.localWeight);
  const enabledMap = useHomeSettings((s) => s.enabled);
  const hideRecents = useHomeSettings((s) => s.hideRecents);
  const hideVideos = useHomeSettings((s) => s.hideVideos);
  const sentinel = useRef<HTMLDivElement>(null);
  const remoteActive = !missing && online;

  // Loads the next shelves before the bottom comes into view; after a failed
  // page it waits for Try Again rather than retrying on every scroll.
  useEffect(() => {
    const el = sentinel.current;
    if (!el || !home.hasNextPage || home.isFetchNextPageError) return;
    const observer = new IntersectionObserver(
      ([entry]) => {
        if (entry?.isIntersecting && !home.isFetchingNextPage) void home.fetchNextPage();
      },
      { root: document.getElementById('scrollable-page'), rootMargin: '600px' },
    );
    observer.observe(el);
    return () => observer.disconnect();
  }, [home.hasNextPage, home.isFetchingNextPage, home.isFetchNextPageError, home.fetchNextPage]);

  // Waiting for the feed's first page (and for the listener's own) before composing, so nothing jumps when it lands.
  if (local.loading || (remoteActive && home.isPending)) return <HomeSkeleton />;

  // YouTube's feed can carry its own, older Recently Played; the history wins, as on the phone.
  // Hide Recents takes the feed's own copy off too (otherwise it would come back when ours goes); Hide videos drops the feed's.
  const allFeed = home.data?.pages.flatMap((page) => page.data) ?? [];
  const feed = hideVideos ? withoutVideos(allFeed) : allFeed;
  const showRecent = !hideRecents && recent.length > 0;
  const remote: Remote[] = remoteActive
    ? [
        ...(showRecent ? [{ kind: 'recent', tracks: recent } as Remote] : []),
        ...feed.filter((shelf) => !((showRecent || hideRecents) && isRecentShelf(shelf))).map((shelf): Remote => ({ kind: 'feed', shelf })),
      ]
    : [];
  const enabled = new Set(LOCAL_SHELF_KINDS.filter((k) => enabledMap[k]));
  let entries: HomeEntry<Remote>[] = compose(remote, local.shelves, { enabled, localWeight: weight }, remoteActive, (r) =>
    r.kind === 'recent' ? r.tracks.length > 0 : r.shelf.items.length > 0,
  );
  // While more feed pages are still to come, the listener's own shelves that did not fit between the pages wait,
  // instead of jumping down the page each time one loads.
  const morePages = remoteActive && home.hasNextPage && !home.isFetchNextPageError;
  // (Nothing is held back when no feed entry exists yet: there would be nothing to wait for, and no sentinel to load more.)
  if (morePages && entries.some((e) => e.side === 'remote')) {
    let last = entries.length;
    while (last > 0 && entries[last - 1]!.side === 'local') last--;
    entries = entries.slice(0, last);
  }

  if (entries.length === 0) {
    return (
      <>
        <PageHeader title="Home" />
        {missing ? (
          <NeedsExtension />
        ) : home.isError ? (
          <LoadError error={home.error} onRetry={() => void home.refetch()} />
        ) : (
          <EmptyState title="Nothing here yet" detail="Add a folder of music, or play something: your own shelves fill in as you listen." />
        )}
        {!missing && (
          <div className="flex justify-center">
            <PrefetchLink to="/local" className="type-callout-emphasized text-key hover:underline">
              Add your music
            </PrefetchLink>
          </div>
        )}
      </>
    );
  }

  return (
    <>
      <PageHeader title="Home" />
      <DjCard />
      {entries.map((entry, i) =>
        entry.side === 'local' ? (
          <LocalShelfView key={`local-${entry.shelf.kind}`} shelf={entry.shelf} tracks={local.tracks} />
        ) : entry.shelf.kind === 'recent' ? (
          <TrackShelf key="recent" title={RECENT_TITLE} to="/library/recent" tracks={entry.shelf.tracks} source={RECENT_SOURCE} />
        ) : (
          <ShelfView key={`${entry.shelf.shelf.title}-${i}`} shelf={entry.shelf.shelf} />
        ),
      )}
      <div ref={sentinel} />
      {home.isFetchingNextPage && <ShelfSkeleton kind="square" />}
      {home.isFetchNextPageError && <LoadMoreError onRetry={() => void home.fetchNextPage()} />}
      {home.isError && !home.data && remoteActive && <LoadError error={home.error} onRetry={() => void home.refetch()} />}
      {missing && (
        <p className="mx-(--body-gutter) my-8 type-callout text-secondary">
          The YouTube Music feed needs the Auden extension.{' '}
          <PrefetchLink to="/extension" className="text-key hover:underline">
            Get the Extension
          </PrefetchLink>
        </p>
      )}
    </>
  );
}
