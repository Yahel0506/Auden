import { fetchTrackLinks } from '@/data/music/api';
import { rememberCover, resolvedCoverOf } from '@/lib/resolvedCovers';
import { usePlayer } from '@/state/player';

/**
 * A song on the player with no cover gets its real one from YouTube Music's own record of the song (its watch-queue
 * row names the catalogue cut, with the album art). Not YouTube's video thumbnail: for a song that is the art on
 * bars of its colour, badly framed. While it is looked up the cover area stays as it is; a song with no catalogue cut
 * keeps showing none.
 */
const RETRY_AFTER_MS = 60_000;
const failedAt = new Map<string, number>();
const inFlight = new Set<string>();
/** YouTube video ids: the only ones YouTube Music can be asked about (not a local file or a clip). */
const YOUTUBE_ID = /^[\w-]{11}$/;

export function startCoverFallback(lookup: (videoId: string) => Promise<{ thumbnail: string | null } | null> = fetchTrackLinks): () => void {
  const check = () => {
    const np = usePlayer.getState().nowPlaying;
    if (!np || np.artworkUrl || !YOUTUBE_ID.test(np.videoId) || inFlight.has(np.videoId)) return;
    const known = resolvedCoverOf(np.videoId);
    if (known) {
      apply(np.videoId, known);
      return;
    }
    if (Date.now() - (failedAt.get(np.videoId) ?? -RETRY_AFTER_MS) < RETRY_AFTER_MS) return;
    inFlight.add(np.videoId);
    void lookup(np.videoId)
      .then((track) => {
        const url = track?.thumbnail;
        if (url) {
          rememberCover(np.videoId, url);
          apply(np.videoId, url);
        } else {
          failedAt.set(np.videoId, Date.now());
        }
      })
      .catch(() => failedAt.set(np.videoId, Date.now()))
      .finally(() => inFlight.delete(np.videoId));
  };
  check();
  return usePlayer.subscribe((s, before) => {
    if (s.nowPlaying?.videoId !== before.nowPlaying?.videoId || (s.nowPlaying && !s.nowPlaying.artworkUrl)) check();
  });
}

function apply(videoId: string, url: string): void {
  usePlayer.setState((s) => (s.nowPlaying?.videoId === videoId && !s.nowPlaying.artworkUrl ? { nowPlaying: { ...s.nowPlaying, artworkUrl: url } } : s));
}

/** For tests: forget what failed and what is in flight. */
export function resetCoverFallback(): void {
  failedAt.clear();
  inFlight.clear();
}
