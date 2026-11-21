import { t as translateUi } from '@/i18n';
import { ChevronLeft, ChevronRight, Download, ListEnd, ListPlus, ListStart, Mic2, MoreHorizontal, Search, Star } from 'lucide-react';
import { startLyricsBatch } from '@/auden/lyrics/batch';
import { canKeepOffline, removeOfflineCopies, saveForOffline } from '@/auden/offline/actions';
import { useOfflineAvailable, useOfflineStatus } from '@/auden/offline/hooks';
import { useFolderOffer } from '@/auden/offline/folder/hooks';
import { announce } from '@/auden/offline/folder/report';
import { saveSongsToFolder } from '@/auden/offline/folder/state';
import { toBatchSongs, toLyricsTrack } from './LyricsBatchButton';
import { isShareable, sourceOf } from '@/playback/source';
import { lyricsKeyFor } from '@/auden/lyrics/key';
import { useSavedLyricsDialog } from '@/auden/lyrics/dialog';
import { useCallback, useEffect, useState } from 'react';
import { Menu, MenuHeader, MenuItem, MenuSeparator, type MenuPlacement } from '@/components/Menu';
import {
  useAddToPlaylist,
  useLikeStatus,
  usePlaylistOptions,
  useSetLiked,
  useSignedIn,
} from '@/data/music/queries';
import type { Track } from '@/data/music/types';
import { useMatchDialog } from '@/data/spotify/matchDialog';
import { useParty } from '@/party/listenTogether';
import { usePlayer } from '@/state/player';
import { DevicePlaylistChoices } from '@/auden/playlists/ui/DevicePlaylistChoices';

function PlaylistChoices({ videoId, onDone, onBack }: { videoId: string; onDone: () => void; onBack: () => void }) {
  const options = usePlaylistOptions(videoId, true);
  const add = useAddToPlaylist();
  return (
    <>
      <MenuItem icon={<ChevronLeft size={14} />} onSelect={onBack}>{translateUi("track_menu.back")}</MenuItem>
      <MenuSeparator />
      <MenuHeader>{translateUi("track_menu.add_to_playlist")}</MenuHeader>
      {options.isPending && (
        <MenuItem disabled onSelect={() => {}}>{translateUi("track_menu.loading")}</MenuItem>
      )}
      {options.isError && (
        <MenuItem disabled onSelect={() => {}}>{translateUi("track_menu.couldn_t_load_your_playlists")}</MenuItem>
      )}
      {options.data?.length === 0 && (
        <MenuItem disabled onSelect={() => {}}>{translateUi("track_menu.no_playlists_yet")}</MenuItem>
      )}
      {options.data?.map((option) => (
        <MenuItem
          key={option.playlistId}
          onSelect={() => {
            add.mutate({ playlistId: option.playlistId, videoId });
            onDone();
          }}
        >
          {option.title}
        </MenuItem>
      ))}
    </>
  );
}

/** Favorite and Add to Playlist: the account's actions, signed in only. */
function AccountItems({ track, onClose, onPlaylists }: { track: Track; onClose: () => void; onPlaylists: () => void }) {
  const status = useLikeStatus(track.videoId);
  const setLiked = useSetLiked();
  const liked = status.data === 'LIKE';
  return (
    <>
      <MenuSeparator />
      <MenuItem
        icon={<Star size={15} fill={liked ? 'currentColor' : 'none'} />}
        disabled={status.isPending}
        onSelect={() => {
          setLiked.mutate({ videoId: track.videoId, liked: !liked });
          onClose();
        }}
      >
        {translateUi(liked ? 'music.unfavorite' : 'music.favorite')}
      </MenuItem>
      <MenuItem icon={<ListPlus size={15} />} trailing={<ChevronRight size={14} />} onSelect={onPlaylists}>{translateUi("track_menu.add_to_playlist")}</MenuItem>
    </>
  );
}

/** Save for offline listening / Remove the copy: only when the feature is on, the extension can fetch, and the track is a YouTube song. */
function OfflineItem({ track, onClose }: { track: Track; onClose: () => void }) {
  const available = useOfflineAvailable();
  const status = useOfflineStatus(track.videoId);
  const folder = useFolderOffer(status === 'kept');
  if (!canKeepOffline(track) || (!available && status === 'none')) return null;
  const toFolder = folder && (
    // The click is the user gesture the permission prompt needs: nothing awaits before the request inside it.
    <MenuItem icon={<Download size={15} />} onSelect={() => (onClose(), void saveSongsToFolder([track]).then(announce))}>{translateUi("track_menu.save_to_folder")}</MenuItem>
  );
  if (status === 'kept') {
    return (
      <>
        <MenuItem icon={<Download size={15} />} onSelect={() => (onClose(), void removeOfflineCopies([track.videoId]))}>{translateUi("track_menu.remove_offline_copy")}</MenuItem>
        {toFolder}
      </>
    );
  }
  return (
    <>
      <MenuItem icon={<Download size={15} />} disabled={status === 'queued'} onSelect={() => (onClose(), void saveForOffline([track]))}>
        {status === 'queued' ? translateUi("components_music_trackmenu_saving_for_offline") : translateUi("components_music_trackmenu_save_for_offline")}
      </MenuItem>
      {toFolder}
    </>
  );
}

function TrackMenuItems({ track, onClose, onSavedLyrics }: { track: Track; onClose: () => void; onSavedLyrics: () => void }) {
  const [view, setView] = useState<'main' | 'playlists' | 'device'>('main');
  const signedIn = useSignedIn();
  // A party plays one YouTube stream for everyone, so a file only this browser has can't join its queue.
  // (Every hook above the early return below: the view switch must not change how many run.)
  const inParty = useParty((s) => !!s.code);
  if (view === 'device') {
    return <DevicePlaylistChoices track={track} onDone={onClose} onBack={() => setView('main')} />;
  }
  if (view === 'playlists') {
    return <PlaylistChoices videoId={track.videoId} onDone={onClose} onBack={() => setView('main')} />;
  }
  // A Spotify row with no YouTube Music song yet has nothing to queue, like or look lyrics up for: only its match can be fixed.
  if (track.spotifyId && track.unavailable) {
    return (
      <MenuItem icon={<Search size={15} />} onSelect={() => (onClose(), useMatchDialog.getState().show(track))}>{translateUi("track_menu.fix_spotify_match")}</MenuItem>
    );
  }
  const { playNext, addToQueue } = usePlayer.getState();
  const blocked = inParty && !isShareable(track);
  return (
    <>
      <MenuItem icon={<ListStart size={15} />} disabled={blocked} onSelect={() => (onClose(), playNext([track]))}>{translateUi("track_menu.play_next")}</MenuItem>
      <MenuItem icon={<ListEnd size={15} />} disabled={blocked} onSelect={() => (onClose(), addToQueue([track]))}>{translateUi("track_menu.add_to_queue")}</MenuItem>
      {blocked && <p className="px-3 pb-1.5 type-callout text-secondary">{translateUi("track_menu.files_on_this_device_can")}</p>}
      <MenuItem
        icon={<Mic2 size={15} />}
        disabled={!track.durationSec}
        onSelect={() => (onClose(), startLyricsBatch(toBatchSongs([track]), track.title))}
      >{translateUi("track_menu.find_lyrics")}</MenuItem>
      <MenuItem icon={<Mic2 size={15} />} disabled={sourceOf(track) === 'clip'} onSelect={onSavedLyrics}>{translateUi("track_menu.saved_lyrics")}</MenuItem>
      <OfflineItem track={track} onClose={onClose} />
      {track.spotifyId && (
        <MenuItem icon={<Search size={15} />} onSelect={() => (onClose(), useMatchDialog.getState().show(track))}>{translateUi("track_menu.fix_spotify_match")}</MenuItem>
      )}
      <MenuItem icon={<ListPlus size={15} />} trailing={<ChevronRight size={14} />} disabled={sourceOf(track) === 'clip' || !!track.unavailable} onSelect={() => setView('device')}>{translateUi("track_menu.add_to_playlist_on_this")}</MenuItem>
      {signedIn && isShareable(track) && <AccountItems track={track} onClose={onClose} onPlaylists={() => setView('playlists')} />}
    </>
  );
}

/**
 * The ⋯ button at the end of a track row (report §14): Play Next and Add to
 * Queue for everyone; Favorite and Add to Playlist signed in.
 */
export function TrackMenu({
  track,
  className,
  placement,
  onOpenChange,
}: {
  track: Track;
  className?: string;
  /** Where the menu opens from the button; the player's sit at the bottom of the window. */
  placement?: MenuPlacement;
  /** Told when the menu opens and closes; most mounts don't need to know. */
  onOpenChange?: (open: boolean) => void;
}) {
  const [anchor, setAnchor] = useState<HTMLElement | null>(null);
  const close = useCallback(() => setAnchor(null), []);
  useEffect(() => {
    onOpenChange?.(anchor !== null);
  }, [anchor, onOpenChange]);
  return (
    <>
      <button
        className={className}
        aria-label={`More options for ${track.title}`}
        aria-haspopup="menu"
        aria-expanded={anchor !== null}
        data-open={anchor !== null || undefined}
        onClick={(event) => {
          event.stopPropagation();
          setAnchor(anchor ? null : event.currentTarget);
        }}
        onDoubleClick={(event) => event.stopPropagation()}
      >
        <MoreHorizontal size={18} strokeWidth={2} />
      </button>
      {anchor && (
        <Menu anchor={anchor} placement={placement} onClose={close} label={track.title}>
          <TrackMenuItems
            track={track}
            onClose={close}
            onSavedLyrics={() => {
              close();
              // Opens at app level (shell/LyricsManage SavedLyricsHost): this menu may unmount with its row first.
              void lyricsKeyFor(track.videoId).then((key) => useSavedLyricsDialog.getState().show(key, toLyricsTrack(track)));
            }}
          />
        </Menu>
      )}
    </>
  );
}
