import { Mic2 } from 'lucide-react';
import { useState } from 'react';
import { startLyricsBatch, type BatchSong } from '@/auden/lyrics/batch';
import type { LyricsTrack } from '@/lyrics/repository';
import type { ArtistRef, Track } from '@/data/music/types';
import { detailButton } from './DetailHeader';

/** A list's tracks as the lyrics batch wants them; album rows carry no artist of their own, so the album's stands in. */
export function toBatchSongs(tracks: readonly Track[], fallbackArtists: readonly ArtistRef[] = []): BatchSong[] {
  return tracks
    .filter((t) => !t.unavailable)
    .map((t) => ({
      videoId: t.videoId,
      title: t.title,
      artist: (t.artists.length ? t.artists : fallbackArtists).map((a) => a.name).join(', '),
      durationSec: t.durationSec,
      album: t.album?.name ?? null,
      source: t.source,
    }));
}

/** A track as the lyrics lookup wants it. */
export function toLyricsTrack(track: Track): LyricsTrack {
  return {
    videoId: track.videoId,
    title: track.title,
    artist: track.artists.map((a) => a.name).join(', '),
    durationMs: Math.round((track.durationSec ?? 0) * 1000),
    album: track.album?.name ?? null,
  };
}

/**
 * "Find lyrics" for the whole list, in the background (the phone's album and playlist menus have it
 * too). [loadAll] brings in the rest of a list that loads as you scroll, so the run covers all of it.
 */
export function LyricsBatchButton({
  tracks,
  title,
  fallbackArtists,
  loadAll,
}: {
  tracks: readonly Track[];
  title: string;
  fallbackArtists?: readonly ArtistRef[];
  loadAll?: () => Promise<readonly Track[]>;
}) {
  const [loading, setLoading] = useState(false);
  return (
    <button
      className={detailButton.circle}
      aria-label="Find lyrics for these songs"
      title="Find lyrics for these songs"
      disabled={tracks.length === 0 || loading}
      onClick={async () => {
        let all = tracks;
        if (loadAll) {
          setLoading(true);
          try {
            all = await loadAll();
          } catch {
            // Fall back to what is loaded.
          } finally {
            setLoading(false);
          }
        }
        startLyricsBatch(toBatchSongs(all, fallbackArtists), title);
      }}
    >
      <Mic2 size={16} />
    </button>
  );
}
