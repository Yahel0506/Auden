import { getAudio, getPlayhead, isMirrored, onTrackEnded, usePlayer } from '@/state/player';
import type { QueueEntry } from '@/playback/queue/timeline';
import { flushListening, listeningGeneration, onDiscardPendingListening } from './listening';
import { PlayRecorder } from './playRecorder';
import type { SongInfo } from './bucket';

/**
 * Records what is listened to into the local listening store (Phase 0.3). Private to this browser:
 * not gated on the extension, a signed-in account or the "share listening" setting. Mounted once
 * from the app shell next to the YouTube history reporting.
 */

const SAMPLE_MS = 5_000;
/** Pending listening is written about every this many samples, and when the tab is hidden. */
const FLUSH_EVERY = 6;

function songOf(entry: QueueEntry | undefined, fallback: { videoId: string; title: string; artist: string; artworkUrl: string | null }): SongInfo {
  const track = entry?.track.videoId === fallback.videoId ? entry.track : undefined;
  return {
    videoId: fallback.videoId,
    title: fallback.title,
    artist: track?.artists.length ? track.artists.map((a) => a.name).join(', ') : fallback.artist,
    albumName: track?.album?.name ?? null,
    albumId: track?.album?.id ?? null,
    artistId: track?.artists[0]?.id ?? null,
    thumbnailUrl: durable(track?.thumbnail ?? fallback.artworkUrl),
  };
}

/** A blob: cover (a local file's, made this session) dies with the page; it must not outlive it in saved stats. */
const durable = (url: string | null | undefined): string | null => (url && !url.startsWith('blob:') ? url : null);

export function startPlayRecorder(): () => void {
  const recorder = new PlayRecorder();
  let samples = 0;
  let flushing: Promise<void> = Promise.resolve();

  const flush = () => {
    const taken = recorder.take();
    if (taken.size === 0) return flushing;
    const takenIn = listeningGeneration();
    // One at a time, in order. A failed write loses at most this batch (about thirty seconds).
    flushing = flushing.then(() => flushListening(taken, takenIn)).catch((error: unknown) => console.warn('Auden: saving listening failed', error));
    return flushing;
  };

  const sample = () => {
    const { status, nowPlaying, queue, index } = usePlayer.getState();
    // Not audio.paused: during an Automix blend the element underneath is paused while music plays.
    // A song that plays on another device (Auden Connect's mirror) is not listening done here: it pauses the clock.
    if (isMirrored() || status !== 'playing' || !nowPlaying || getPlayhead()?.paused !== false) {
      recorder.onPause();
      // Like the phone's onStopped(): what a pause leaves unwritten is saved now, not at the next flush.
      void flush();
      return;
    }
    // A DJ line is not listening to music: it pauses the clock like a stall does.
    if (nowPlaying.source === 'clip') {
      recorder.onPause();
      return;
    }
    const audio = getAudio();
    const decoded = audio && Number.isFinite(audio.duration) ? audio.duration * 1000 : 0;
    const durationMs = decoded > 0 ? decoded : nowPlaying.durationSec * 1000;
    recorder.onSample(songOf(queue[index], nowPlaying), durationMs, performance.now(), new Date());
    if (++samples >= FLUSH_EVERY) {
      samples = 0;
      void flush();
    }
  };

  const timer = setInterval(sample, SAMPLE_MS);
  const stopEnded = onTrackEnded(() => recorder.onTrackEnded());
  // A backup restore replaced the listening: what was collected before it is dropped, not merged in.
  const stopDiscard = onDiscardPendingListening(() => void recorder.take());
  const onHide = () => {
    if (document.visibilityState === 'hidden') void flush();
  };
  const onPageHide = () => void flush();
  document.addEventListener('visibilitychange', onHide);
  window.addEventListener('pagehide', onPageHide);

  return () => {
    clearInterval(timer);
    stopEnded();
    stopDiscard();
    document.removeEventListener('visibilitychange', onHide);
    window.removeEventListener('pagehide', onPageHide);
    void flush();
  };
}
