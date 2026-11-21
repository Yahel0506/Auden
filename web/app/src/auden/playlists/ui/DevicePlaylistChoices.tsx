import { t as translateUi } from '@/i18n';
import { ChevronLeft } from 'lucide-react';
import { MenuHeader, MenuItem, MenuSeparator } from '@/components/Menu';
import type { Track } from '@/data/music/types';
import { addToPlaylist, newPlaylist } from '../actions';
import { useLocalPlaylists, useMembership } from '../store';

/** "Add to Playlist" for playlists on this device: shows which already hold the song, and works signed out. */
export function DevicePlaylistChoices({ track, onDone, onBack }: { track: Track; onDone: () => void; onBack: () => void }) {
  const lists = useLocalPlaylists();
  const has = useMembership(track).data;
  return (
    <>
      <MenuItem icon={<ChevronLeft size={14} />} onSelect={onBack}>{translateUi("device_playlist_choices.back")}</MenuItem>
      <MenuSeparator />
      <MenuHeader>{translateUi("device_playlist_choices.playlists_on_this_device")}</MenuHeader>
      <MenuItem
        onSelect={() => {
          onDone();
          void newPlaylist([track]);
        }}
      >{translateUi("device_playlist_choices.new_playlist")}</MenuItem>
      {lists.data?.map(({ playlist }) => {
        const holds = has?.has(playlist.id) ?? false;
        return (
          <MenuItem
            key={playlist.id}
            checked={holds}
            onSelect={() => {
              // Already there: nothing to add (the store would skip it), so just close.
              if (!holds) void addToPlaylist(playlist.id, [track]);
              onDone();
            }}
          >
            {playlist.title}
          </MenuItem>
        );
      })}
    </>
  );
}
