import { useState } from 'react';
import { useNavigate, useParams } from 'react-router';
import { Artwork } from '@/components/music/Artwork';
import { EmptyState } from '@/components/music/States';
import { playList, shuffleList } from '@/components/music/play';
import { TrackMenu } from '@/components/music/TrackMenu';
import { Menu, MenuItem } from '@/components/Menu';
import type { Track } from '@/data/music/types';
import { downloadPlaylist, duplicate, moveInPlaylist, removeFromPlaylist, removePlaylist, renameWithPrompt } from '@/auden/playlists/actions';
import { useLocalPlaylist, useLocalPlaylists, usePlaylistEntries } from '@/auden/playlists/store';
import { OfflineButton } from '@/auden/offline/ui/OfflineButton';
import { useCoverOf } from '@/auden/playlists/ui/cover';
import { usePlayer } from '@/state/player';
import { dropBefore } from '@/auden/playlists/order';

const pill = 'inline-flex h-8 items-center rounded-pill bg-quinary px-4 type-body-emphasized text-primary hover:bg-tracklist-hover';

/** A playlist kept on this device: songs from files and from YouTube, in the order the listener chose. */
export default function LocalPlaylistPage() {
  const id = useParams().id;
  const navigate = useNavigate();
  const playlist = useLocalPlaylist(id);
  const summaries = useLocalPlaylists();
  const { items, loading } = usePlaylistEntries(id);
  const summary = summaries.data?.find((s) => s.playlist.id === id);
  const cover = useCoverOf(summary);
  const nowPlaying = usePlayer((s) => s.nowPlaying?.videoId);
  const [dragging, setDragging] = useState<number | null>(null);
  const [over, setOver] = useState<number | null>(null);
  const [menu, setMenu] = useState<HTMLElement | null>(null);

  if (!id) return <EmptyState title="Playlist not found" />;
  if (playlist.isPending || loading) return null;
  if (!playlist.data) return <EmptyState title="Playlist not found" detail="It may have been deleted." />;
  const title = playlist.data.title;
  const playable = items.flatMap((i) => (i.track ? [i.track] : []));
  const context = { source: { title, type: 'library' as const, id } };
  const indexOf = (t: Track) => playable.indexOf(t);

  return (
    <div className="pb-10">
      <div className="mx-(--body-gutter) flex items-end gap-6 pt-8 max-sm:flex-col max-sm:items-start">
        <div className="w-[180px] shrink-0">
          <Artwork src={cover} displayWidth={180} radius="var(--radius-artwork)" eager />
        </div>
        <div className="min-w-0">
          <p className="type-callout text-secondary">Playlist on this device</p>
          <h1 className="type-header-emphasized truncate">{title}</h1>
          <p className="type-callout text-secondary">
            {items.length} {items.length === 1 ? 'song' : 'songs'}
            {playlist.data.sourceId ? ' · saved copy: new songs from the original are added automatically' : ''}
          </p>
          <div className="mt-3 flex flex-wrap gap-2.5">
            <button className={pill} disabled={!playable.length} onClick={() => playList(playable, 0, context)}>
              Play
            </button>
            <button className={pill} disabled={!playable.length} onClick={() => shuffleList(playable, context)}>
              Shuffle
            </button>
            <button className={pill} onClick={() => void renameWithPrompt(id, title)}>
              Rename
            </button>
            <button className={pill} onClick={() => void duplicate(id).then((copy) => copy && navigate(`/local/playlist/${encodeURIComponent(copy)}`))}>
              Duplicate
            </button>
            {/* Watched for new songs afterwards, when "Keep playlists updated" is on (Settings > Offline copies). */}
            <OfflineButton tracks={playable} collection={{ kind: 'local', id, title }} />
            <button className={pill} aria-haspopup="menu" onClick={(e) => setMenu(e.currentTarget)}>
              Export
            </button>
            <button
              className={pill}
              onClick={() => {
                if (window.confirm(`Delete “${title}”? The songs stay in your library.`)) void removePlaylist(id).then(() => navigate('/local?tab=playlists'));
              }}
            >
              Delete
            </button>
          </div>
        </div>
      </div>
      {menu && (
        <Menu anchor={menu} onClose={() => setMenu(null)} label="Export playlist">
          <MenuItem onSelect={() => (setMenu(null), void downloadPlaylist(id, 'M3U8'))}>M3U8 (songs with files)</MenuItem>
          <MenuItem onSelect={() => (setMenu(null), void downloadPlaylist(id, 'XSPF'))}>XSPF</MenuItem>
          <MenuItem onSelect={() => (setMenu(null), void downloadPlaylist(id, 'AUDEN'))}>Auden JSON (keeps everything)</MenuItem>
        </Menu>
      )}

      {items.length === 0 ? (
        <EmptyState title="No songs yet" detail="Use ••• on any song and choose Add to Playlist on This Device." />
      ) : (
        <ul className="mx-(--body-gutter) mt-6" aria-label="Songs">
          {items.map((item) => {
            const track = item.track;
            return (
              <li
                key={item.entryId}
                draggable
                data-over={over === item.entryId || undefined}
                data-current={(track && nowPlaying === track.videoId) || undefined}
                onDragStart={(e) => {
                  setDragging(item.entryId);
                  e.dataTransfer.effectAllowed = 'move';
                }}
                onDragOver={(e) => {
                  if (dragging === null) return;
                  e.preventDefault();
                  setOver(item.entryId);
                }}
                onDragEnd={() => (setDragging(null), setOver(null))}
                onDrop={(e) => {
                  e.preventDefault();
                  // Moved by entry id, never by index: the list may have changed under a stale drag.
                  if (dragging !== null && dragging !== item.entryId) void moveInPlaylist(id, dragging, dropBefore(items, dragging, item.entryId));
                  setDragging(null);
                  setOver(null);
                }}
                className="group/row relative flex h-[54px] items-center gap-3 rounded-[12px] px-2 hover:bg-tracklist-hover data-current:bg-quinary data-over:outline-2 data-over:outline-key"
                style={{ contentVisibility: 'auto', containIntrinsicSize: '54px' }}
                onDoubleClick={() => track && playList(playable, indexOf(track), context)}
              >
                <span className="cursor-grab text-tertiary" aria-hidden="true">⠿</span>
                <div className="w-10 shrink-0">
                  <Artwork src={track?.thumbnail ?? null} displayWidth={40} radius="var(--radius-artwork-small)" />
                </div>
                <span className="min-w-0 flex-1">
                  <span className="block truncate type-body text-primary">{track?.title ?? item.row?.title ?? 'Unavailable'}</span>
                  <span className="block truncate type-callout text-secondary">
                    {track ? track.artists.map((a) => a.name).join(', ') : 'This song has no file or YouTube version here.'}
                  </span>
                </span>
                <button className="type-callout text-secondary opacity-0 group-hover/row:opacity-100 focus-visible:opacity-100" aria-label={`Remove ${track?.title ?? 'song'}`} onClick={() => void removeFromPlaylist(id, item.entryId)}>
                  Remove
                </button>
                {track && <TrackMenu track={track} className="grid size-7 place-items-center rounded-full text-key hover:bg-quinary" />}
              </li>
            );
          })}
        </ul>
      )}
    </div>
  );
}
