import { useEffect, useState } from 'react';
import { searchSongs } from '@/data/music/api';
import type { Track } from '@/data/music/types';
import { useMatchDialog } from '@/data/spotify/matchDialog';
import { setManualMatch } from '@/data/spotify/matches';
import { Modal } from './LyricsManage';

const field = 'w-full rounded-[10px] border-0 bg-quinary px-3 py-2 type-body text-primary';

const artistsOf = (t: Track) => t.artists.map((a) => a.name).join(', ');

/** "Fix Spotify match": search YouTube Music, pick the right song (SpotifyMatchSheet.kt). The pick is remembered for good. */
function MatchDialog({ row, onClose }: { row: Track; onClose: () => void }) {
  const [query, setQuery] = useState([row.title, artistsOf(row)].filter(Boolean).join(' '));
  const [results, setResults] = useState<Track[] | null>(null);
  const [failed, setFailed] = useState(false);

  // The search runs as the text settles, and a late answer for an older text is dropped.
  useEffect(() => {
    const text = query.trim();
    if (!text) {
      setResults([]);
      return;
    }
    let current = true;
    setResults(null);
    setFailed(false);
    const timer = setTimeout(() => {
      searchSongs(text)
        .then((found) => current && setResults(found))
        .catch(() => current && (setFailed(true), setResults([])));
    }, 350);
    return () => {
      current = false;
      clearTimeout(timer);
    };
  }, [query]);

  const choose = (track: Track) => {
    if (row.spotifyId) void setManualMatch(row.spotifyId, track);
    onClose();
  };

  return (
    <Modal title="Fix Spotify match" onClose={onClose}>
      <p className="mb-2 type-callout text-secondary">
        Which YouTube Music song is “{row.title}”{artistsOf(row) ? ` by ${artistsOf(row)}` : ''}?
      </p>
      <input className={field} value={query} onChange={(e) => setQuery(e.currentTarget.value)} placeholder="Search YouTube Music" autoFocus />
      <ul className="mt-3 overflow-hidden rounded-[10px] bg-quinary">
        {results === null && <li className="px-3 py-3 type-callout text-secondary">Searching…</li>}
        {results?.length === 0 && <li className="px-3 py-3 type-callout text-secondary">{failed ? 'Couldn’t search YouTube Music.' : 'Nothing found.'}</li>}
        {results?.map((track) => (
          <li key={track.videoId} className="[&+&]:border-t-[0.5px] [&+&]:border-t-divider">
            <button className="flex w-full flex-col items-start px-3 py-2 text-start hover:bg-quaternary" onClick={() => choose(track)}>
              <span className="type-body-emphasized">{track.title}</span>
              <span className="type-callout text-secondary">{[artistsOf(track), track.album?.name].filter(Boolean).join(' · ')}</span>
            </button>
          </li>
        ))}
      </ul>
    </Modal>
  );
}

/** The one dialog, opened from any Spotify row's menu; mounted once in the shell. */
export function SpotifyMatchHost() {
  const open = useMatchDialog((s) => s.open);
  const hide = useMatchDialog((s) => s.hide);
  return open ? <MatchDialog row={open} onClose={hide} /> : null;
}
