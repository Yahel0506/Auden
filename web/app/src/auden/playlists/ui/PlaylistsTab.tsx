import { useRef, useState } from 'react';
import { useNavigate } from 'react-router';
import { Menu, MenuItem } from '@/components/Menu';
import { PrefetchLink } from '@/components/PrefetchLink';
import { createPlaylist as createYouTubePlaylist } from '@/data/music/api';
import { useSignedIn } from '@/data/music/queries';
import { useJobs } from '../../jobs/jobs';
import { askName } from './askName';
import { Artwork } from '@/components/music/Artwork';
import { importFiles, newPlaylist } from '../actions';
import { useLocalPlaylists, type PlaylistSummary } from '../store';
import { useCoverOf } from './cover';

const button = 'type-callout-emphasized text-key hover:underline disabled:opacity-50';
const ACCEPT = '.m3u,.m3u8,.xspf,.json,.auden.json';

function Row({ summary }: { summary: PlaylistSummary }) {
  const cover = useCoverOf(summary);
  const { playlist, count } = summary;
  return (
    <li>
      <PrefetchLink to={`/local/playlist/${encodeURIComponent(playlist.id)}`} className="flex h-[60px] items-center gap-3 rounded-[12px] px-2 hover:bg-tracklist-hover">
        <div className="w-11 shrink-0">
          <Artwork src={cover} displayWidth={44} radius="var(--radius-artwork-small)" />
        </div>
        <span className="min-w-0 flex-1">
          <span className="block truncate type-body-emphasized text-primary">{playlist.title}</span>
          <span className="block truncate type-callout text-secondary">
            {count} {count === 1 ? 'song' : 'songs'}
            {playlist.sourceId ? ' · saved copy' : ''}
          </span>
        </span>
      </PrefetchLink>
    </li>
  );
}

/** Playlists on this device, with import by picking or dropping playlist files. */
export function PlaylistsTab() {
  const lists = useLocalPlaylists();
  const input = useRef<HTMLInputElement>(null);
  const [dropping, setDropping] = useState(false);
  const [online, setOnline] = useState(false);
  const signedIn = useSignedIn();
  const navigate = useNavigate();
  const [chooser, setChooser] = useState<HTMLElement | null>(null);
  const createOnYouTube = async () => {
    const name = await askName('New Playlist on YouTube Music', '', 'Create');
    if (!name) return;
    const jobId = `create:${name}`;
    useJobs.getState().start({ id: jobId, title: `Creating “${name}”`, total: 1 });
    try {
      const id = await createYouTubePlaylist(name, [], '');
      useJobs.getState().finish(jobId, `Created “${name}” on YouTube Music.`);
      navigate(`/playlist/${encodeURIComponent(`VL${id}`)}`);
    } catch (error) {
      useJobs.getState().finish(jobId, `Couldn’t create it on YouTube Music: ${error instanceof Error ? error.message : String(error)}`);
    }
  };
  const run = (files: File[]) => {
    if (files.length) void importFiles(files, { online });
  };
  return (
    <div
      className="mx-(--body-gutter) rounded-[12px] data-dropping:outline-2 data-dropping:outline-dashed data-dropping:outline-key"
      data-dropping={dropping || undefined}
      onDragOver={(e) => {
        if (e.dataTransfer.types.includes('Files')) {
          e.preventDefault();
          setDropping(true);
        }
      }}
      onDragLeave={() => setDropping(false)}
      onDrop={(e) => {
        if (!e.dataTransfer.files.length) return;
        e.preventDefault();
        setDropping(false);
        // Only playlist files: a dropped mp3 would be read as an M3U and fail at the end.
        run([...e.dataTransfer.files].filter((f) => /\.(m3u8?|xspf|json)$/i.test(f.name)));
      }}
    >
      <div className="flex flex-wrap items-center gap-4 pb-3">
        <button
          className={button}
          aria-haspopup={signedIn ? 'menu' : undefined}
          onClick={(e) => (signedIn ? setChooser(e.currentTarget) : void newPlaylist())}
        >
          New Playlist
        </button>
        {chooser && (
          <Menu anchor={chooser} onClose={() => setChooser(null)} label="New playlist">
            <MenuItem onSelect={() => (setChooser(null), void newPlaylist().then((id) => id && navigate(`/local/playlist/${encodeURIComponent(id)}`)))}>On This Device</MenuItem>
            <MenuItem onSelect={() => (setChooser(null), void createOnYouTube())}>On YouTube Music</MenuItem>
          </Menu>
        )}
        <button className={button} onClick={() => input.current?.click()}>Import…</button>
        <label className="flex items-center gap-1.5 type-callout text-secondary">
          <input type="checkbox" checked={online} onChange={(e) => setOnline(e.target.checked)} />
          Search YouTube Music for songs that aren’t in your library
        </label>
        <input ref={input} type="file" hidden multiple accept={ACCEPT} onChange={(e) => { run([...(e.target.files ?? [])]); e.target.value = ''; }} />
      </div>
      {lists.data?.length ? (
        <ul aria-label="Playlists">{lists.data.map((s) => <Row key={s.playlist.id} summary={s} />)}</ul>
      ) : (
        <p className="py-10 text-center type-callout text-secondary">No playlists yet. Create one, import an M3U8, XSPF or Auden JSON file, or drop one here.</p>
      )}
    </div>
  );
}
