import { PrefetchLink } from '@/components/PrefetchLink';
import { Artwork } from '@/components/music/Artwork';
import { ShelfScroller, ShelfTitle, TrackShelf } from '@/components/music/ShelfView';
import { shelfSection } from '@/components/shelf';
import type { Track } from '@/data/music/types';
import { LocalShelfKind, type LocalShelf } from '../shelves';

/**
 * One of the listener's own shelves. Songs go through TrackShelf with the real `Track`s (a local song keeps
 * its `source` and synthetic id, which a MusicItem cannot carry), and tapping one plays the shelf from there.
 * Playlists are cards that open the playlist.
 */
export function LocalShelfView({ shelf, tracks }: { shelf: LocalShelf; tracks: ReadonlyMap<string, Track> }) {
  if (shelf.kind === LocalShelfKind.PLAYLISTS) {
    return (
      <section className={shelfSection} aria-label={shelf.title}>
        <ShelfTitle title={shelf.title} to="/local?tab=playlists" />
        <ShelfScroller kind="square">
          {shelf.items.map((item) => (
            <li key={item.id}>
              <PrefetchLink to={`/local/playlist/${encodeURIComponent(item.id)}`} className="group/square flex min-w-0 flex-col gap-2.5">
                <Artwork src={item.art} displayWidth={200} radius="var(--radius-artwork)" />
                <span className="flex min-w-0 flex-col">
                  <span className="truncate type-body group-hover/square:underline">{item.title}</span>
                  <span className="truncate type-callout text-secondary">{item.subtitle}</span>
                </span>
              </PrefetchLink>
            </li>
          ))}
        </ShelfScroller>
      </section>
    );
  }
  const songs = shelf.items.flatMap((item) => {
    const track = tracks.get(item.id);
    return track ? [track] : [];
  });
  if (!songs.length) return null;
  return <TrackShelf title={shelf.title} tracks={songs} source={{ title: shelf.title, type: 'home' }} />;
}
