import { Maximize, Minimize, X } from 'lucide-react';
import { useEffect, useRef, useState, useSyncExternalStore } from 'react';
import { useLocation } from 'react-router';
import {
  BackwardIcon,
  ForwardIcon,
  LyricsActiveIcon,
  LyricsIcon,
  PauseIcon,
  PlayIcon,
  RepeatIcon,
  RepeatOneIcon,
  ShuffleIcon,
  UpNextIcon,
} from '@/components/icons/Transport';
import { ExplicitBadge } from '@/components/music/ExplicitBadge';
import { FavoriteButton } from '@/components/music/FavoriteButton';
import { TrackMenu } from '@/components/music/TrackMenu';
import { albumHref } from '@/components/music/links';
import { MotionArtwork } from '@/components/music/MotionArtwork';
import { VideoSurface } from '@/components/music/VideoSurface';
import { useTrackMotion } from '@/data/apple/motionArt';
import { sizedArtwork } from '@/lib/artwork';
import { usePlayer } from '@/state/player';
import { useUi } from '@/state/ui';
import { useHasVideo, useVideo } from '@/state/video';
import { Credits, PageLink, pageLink } from './Lcd';
import { LyricsPanel } from './LyricsPanel';
import { CoverBlurBackdrop, MeshScrim } from './CoverBlurBackdrop';
import { useStickyCover } from './useStickyCover';
import { MeshBackdrop } from './MeshBackdrop';
import { usePlayerLook } from './playerLook';
import { DjButton } from '@/auden/dj/ui/DjMenu';
import { DjCaption } from '@/auden/dj/ui/DjCaption';
import { DjScreen, DJ_SCRIM } from '@/auden/dj/ui/DjScreen';
import { DJ_MESH_PACE, MESH_CYCLE_MS } from '@/auden/dj/ui/djScreen';
import { useDjMeshColors, useDjScreen } from '@/auden/dj/ui/useDjScreen';
import { albumPageFor } from './nowPlayingLinks';
import { UpNext } from './UpNext';
import { MixWindowHighlight, NerdStats } from './NerdStats';
import { useScrubber } from './useScrubber';
import { VideoQuality } from './VideoQuality';
import { DevicePicker } from '@/auden/connect/ui/DevicePicker';
import { fadeForVolume, VolumeControl } from './VolumeControl';

function useBrowserFullScreen(): boolean {
  return useSyncExternalStore(
    (onChange) => {
      document.addEventListener('fullscreenchange', onChange);
      return () => document.removeEventListener('fullscreenchange', onChange);
    },
    () => document.fullscreenElement !== null,
  );
}

/** The seek line under the track's names: thicker on hover, with the times beneath its ends. */
function Scrubber() {
  const { bar, elapsed, remaining, mixLabel, dragging, handlers } = useScrubber();
  return (
    <div className="group/scrub mt-1.5" data-dragging={dragging || undefined}>
      {/* A taller hit area than the line it carries. */}
      <div className="group/hit flex h-4 cursor-pointer touch-none items-center" {...handlers}>
        <div
          className="relative h-1 flex-1 overflow-hidden rounded-[999px] bg-quaternary transition-[height] duration-120 ease-in group-hover/hit:h-2 group-data-dragging/scrub:h-2"
          aria-hidden="true"
        >
          <MixWindowHighlight />
          <div
            ref={bar}
            className="relative size-full origin-left scale-x-(--progress) bg-secondary transition-[background-color] duration-120 ease-in [--progress:0] group-hover/hit:bg-primary group-data-dragging/scrub:bg-primary"
          />
        </div>
      </div>
      <div className="relative mt-0.5 flex justify-between type-[500_11px/14px] tabular-nums text-tertiary">
        <span ref={elapsed} />
        <span ref={mixLabel} className="pointer-events-none absolute inset-x-0 text-center text-secondary opacity-0" aria-hidden="true">
          Mixing
        </span>
        <span ref={remaining} />
      </div>
    </div>
  );
}

const transportButton =
  'grid place-items-center rounded-full transition-[background-color,scale] duration-100 ease-in enabled:hover:bg-quinary enabled:active:scale-90 disabled:opacity-40';
// Shuffle and repeat, when on: full white on the mesh, where red would clash.
const toggle = `${transportButton} size-9 text-tertiary aria-pressed:text-primary`;
const skip = `${transportButton} size-12 text-primary`;

/** Shuffle, previous, play/pause, next, repeat. No volume: the pill keeps that. */
function Transport() {
  const hasTrack = usePlayer((s) => s.nowPlaying !== null);
  const playing = usePlayer((s) => s.status === 'playing' || s.status === 'buffering');
  const hasNext = usePlayer((s) => s.index + 1 < s.queue.length || s.repeat !== 'off' || (s.autoplay && s.index >= 0));
  const shuffle = usePlayer((s) => s.shuffle);
  const repeat = usePlayer((s) => s.repeat);
  const { togglePlay, next, previous, toggleShuffle, cycleRepeat } = usePlayer.getState();

  return (
    <div className="mx-auto mt-[clamp(0px,0.5vh,4px)] mb-0 flex h-12 w-[min(100%,315px)] items-center justify-between">
      <button className={toggle} aria-label="Shuffle" aria-pressed={shuffle} onClick={toggleShuffle}>
        <ShuffleIcon size={30} />
      </button>
      <button className={skip} disabled={!hasTrack} aria-label="Previous" onClick={previous}>
        <BackwardIcon size={40} />
      </button>
      <button className={`${transportButton} size-14 text-primary`} disabled={!hasTrack} aria-label={playing ? 'Pause' : 'Play'} onClick={togglePlay}>
        {playing ? <PauseIcon size={48} /> : <PlayIcon size={48} />}
      </button>
      <button className={skip} disabled={!hasTrack || !hasNext} aria-label="Next" onClick={next}>
        <ForwardIcon size={40} />
      </button>
      <button
        className={toggle}
        aria-label={repeat === 'one' ? 'Repeat one' : repeat === 'all' ? 'Repeat all' : 'Repeat'}
        aria-pressed={repeat !== 'off'}
        onClick={cycleRepeat}
      >
        {repeat === 'one' ? <RepeatOneIcon size={30} /> : <RepeatIcon size={30} />}
      </button>
    </div>
  );
}

// Close, browser full screen, the column toggles and the speaker, in the corners.
const chromeButton =
  'grid size-8 place-items-center rounded-full text-secondary transition-[background-color,color] duration-100 ease-in hover:bg-quinary hover:text-primary';
const chrome = `absolute z-1 ${chromeButton}`;
const artworkLayer = 'absolute inset-0 size-full rounded-[inherit] object-cover opacity-0 transition-opacity duration-300 ease-in';

/** How long the pointer can rest before a playing music video hides its controls. */
const IDLE_MS = 3000;

/**
 * The full-screen player (report §7): the artwork on the left with the
 * track's names, the seek line and the transport under it; the lyrics, the
 * same am-lyrics panel as the side panel's, on the right; all over a mesh
 * gradient of the artwork's colours. With the lyrics closed, the artwork
 * column sits in the middle on its own, as Apple's does.
 *
 * A music video plays as Apple's do: on its own, it fills the window with
 * the names and transport over its foot, fading out while the pointer rests;
 * beside the lyrics or Up Next, it takes the artwork's place at 16:9.
 */
export function FullScreenPlayer() {
  const nowPlaying = usePlayer((s) => s.nowPlaying);
  const { previousCover, coverLoaded, coverFailed } = useStickyCover(nowPlaying?.artworkUrl ?? null);
  // The queue's row for what's playing: it carries the album and each artist's channel.
  const track = usePlayer((s) => {
    const entry = s.queue[s.index]?.track;
    return entry && entry.videoId === s.nowPlaying?.videoId ? entry : null;
  });
  // Apple's animated sleeve over the cover, as the phone's now-playing shows it.
  const motion = useTrackMotion(nowPlaying?.title, nowPlaying?.artist, track?.album?.name);
  const { pathname } = useLocation();
  const openedAt = useRef(pathname);
  const playing = usePlayer((s) => s.status === 'playing' || s.status === 'buffering');
  // The DJ's screen (its ring, its words) is up only while the DJ speaks or chooses; when a song plays it is gone and this is
  // the usual player, exactly as for any track (Android: djScreen = pending || (running && the playing item is a DJ clip)).
  const dj = useDjScreen();
  const djColors = useDjMeshColors(dj);
  const setFullScreen = useUi((s) => s.setFullScreen);
  const hasVideo = useHasVideo(nowPlaying?.videoId);
  const video = hasVideo && !dj;
  const theme = usePlayerLook((s) => s.theme);
  // The right-hand column: the lyrics, Up Next, or nothing. Lyrics toggle bottom left, Up Next bottom right.
  // A music video opens on its own, without the lyrics.
  const [panel, setPanel] = useState<'lyrics' | 'queue' | null>(() =>
    nowPlaying && useVideo.getState().videoId === nowPlaying.videoId ? null : 'lyrics',
  );
  // Once the listener picks a column, a video arriving late doesn't take it away.
  const picked = useRef(false);
  const [idle, setIdle] = useState(false);
  const [volumeOpen, setVolumeOpen] = useState(false);
  const idleTimer = useRef<ReturnType<typeof setTimeout> | null>(null);
  const browserFullScreen = useBrowserFullScreen();
  const close = useRef<HTMLButtonElement>(null);
  // Whether this player put the page in the browser's full screen, so closing it leaves it again.
  const enteredBrowserFullScreen = useRef(false);

  // The column follows the toggle alone: whether this track has lyrics, and
  // from which provider, is the panel's own business to say.
  const showLyrics = !dj && panel === 'lyrics';
  const showQueue = !dj && panel === 'queue';
  const togglePanel = (p: 'lyrics' | 'queue') => {
    picked.current = true;
    setPanel((on) => (on === p ? null : p));
  };

  useEffect(() => {
    if (video && !picked.current) setPanel(null);
  }, [video]);

  const wake = () => {
    setIdle(false);
    if (idleTimer.current) clearTimeout(idleTimer.current);
    idleTimer.current = setTimeout(() => setIdle(true), IDLE_MS);
  };
  useEffect(() => {
    wake();
    return () => {
      if (idleTimer.current) clearTimeout(idleTimer.current);
    };
  }, []);

  useEffect(() => {
    const opener = document.activeElement as HTMLElement | null;
    close.current?.focus({ preventScroll: true });
    return () => {
      if (enteredBrowserFullScreen.current && document.fullscreenElement) void document.exitFullscreen();
      opener?.focus({ preventScroll: true });
    };
  }, []);

  // A title, artist or album link went somewhere: get out of the way so the page shows.
  useEffect(() => {
    if (pathname !== openedAt.current) setFullScreen(false);
  }, [pathname, setFullScreen]);

  // Nothing left to show once the queue ends (the DJ's screen can stand without a song while it chooses its first set).
  useEffect(() => {
    if (!nowPlaying && !dj) setFullScreen(false);
  }, [nowPlaying, dj, setFullScreen]);

  const toggleBrowserFullScreen = () => {
    if (document.fullscreenElement) {
      void document.exitFullscreen();
    } else {
      // The whole page, not this overlay: menus and dialogs portal to <body> and must stay visible.
      void document.documentElement.requestFullscreen().then(
        () => (enteredBrowserFullScreen.current = true),
        () => undefined,
      );
    }
  };

  if (!nowPlaying && !dj) return null;
  const { videoId, title, artist } = nowPlaying ?? { videoId: '', title: '', artist: '' };
  const album = track?.album ?? null;
  // With a right-hand column; on narrow windows that column fills the screen and the controls sit under it, without the artwork.
  const column = showLyrics || showQueue;
  // A music video on its own fills the window.
  const theater = video && !column;
  // The DJ's screen is the ring on the moving mesh whatever the theme; a music video on its own covers every backdrop.
  const blurTheme = theme === 'blur' && !dj;
  // ...and hides the controls and the pointer while it plays undisturbed.
  const hidden = theater && playing && idle ? ' pointer-events-none opacity-0' : '';
  const corner = `${chrome} transition-[background-color,color,opacity] duration-300${hidden}`;

  return (
    <div
      // Over everything but menus and dialogs, which portal above it. The on-dark colours come from tokens.css (data-full-screen).
      // --art, the artwork's side: Apple's 461px at 1440px wide, and never taller than the screen leaves room for (the controls under it
      // sit closer together since v1.12, and the cover takes that height back).
      className="fixed inset-0 z-[calc(var(--z-contextual-menus)-2)] isolate animate-enter overflow-hidden text-primary [--art:min(34vw,calc(100dvh-280px),760px)] motion-reduce:[animation-name:fade-in] max-md:[--art:min(84vw,calc(100dvh-310px),420px)]"
      role="dialog"
      aria-modal="true"
      aria-label="Now Playing"
      data-full-screen
      data-paused={!playing || undefined}
      onPointerMove={wake}
      onPointerDown={wake}
      onKeyDown={wake}
      style={theater && playing && idle ? { cursor: 'none' } : undefined}
    >
      {/* Frozen while the blurred cover fully covers it (lyrics or the queue open): its shader would draw pixels nobody sees. */}
      {/* The Blur theme stands on the blurred cover alone, so the mesh (a per-pixel shader) is not even mounted. The DJ's screen is on the mesh
          whatever the theme, never at rest: the DJ's palettes cycle through it, one every 3.6 s, and the blobs keep moving (one instance for
          both states, so the swap to a song's colours is a cross-fade). */}
      {!blurTheme && (
        <MeshBackdrop
          artworkUrl={nowPlaying?.artworkUrl ?? null}
          playing={dj || (playing && (!column || theater))}
          soft={!dj && theme === 'continuation'}
          colors={dj ? djColors : null}
          fadeMs={MESH_CYCLE_MS}
          pace={dj ? DJ_MESH_PACE : 1}
        />
      )}
      {/* Over the mesh: a lighter scrim in the main view, and with the lyrics or the queue open the full-cover blur under a deeper one. */}
      {dj && <div className="pointer-events-none absolute inset-0 -z-1" style={{ background: DJ_SCRIM }} aria-hidden="true" />}
      {!dj && !theater && !blurTheme && <MeshScrim column={column} continuation={theme === 'continuation'} />}
      {!dj && !theater && <CoverBlurBackdrop artworkUrl={nowPlaying?.artworkUrl ?? null} column={column || blurTheme} solid={blurTheme} />}
      {theater && (
        <div className="absolute inset-0 animate-fade-in bg-black">
          <VideoSurface fit="contain" className="size-full" />
        </div>
      )}

      {dj ? <DjScreen /> : <DjCaption />}
      <button ref={close} className={`${corner} top-4 left-5`} aria-label="Close" onClick={() => setFullScreen(false)}>
        <X size={20} />
      </button>
      {/* The music video's quality, just inside the full-screen button. */}
      {video && (
        <VideoQuality className={`absolute top-[18px] z-1${hidden}${document.fullscreenEnabled ? ' right-15' : ' right-5'}`} />
      )}
      {document.fullscreenEnabled && (
        <button
          className={`${corner} top-4 right-5`}
          aria-label={browserFullScreen ? 'Exit full screen' : 'Enter full screen'}
          onClick={toggleBrowserFullScreen}
        >
          {browserFullScreen ? <Minimize size={18} /> : <Maximize size={18} />}
        </button>
      )}

      {/* Apple's grid areas "controls lyrics": the artwork column, then the lyrics. */}
      {!dj && nowPlaying && (
      <div
        className={
          theater
            ? `absolute inset-x-0 bottom-0 flex justify-center bg-[linear-gradient(to_top,rgb(0_0_0/0.72),rgb(0_0_0/0.35)_55%,transparent)] px-6 pt-32 pb-16 transition-opacity duration-300${hidden}`
            : `grid h-full items-center justify-center gap-x-[clamp(48px,7vw,144px)] px-[clamp(24px,9vw,144px)] py-16 max-md:px-6 max-md:pb-18 ${
                column
                  ? 'grid-cols-[var(--art)_minmax(0,38vw)] max-md:grid-cols-[minmax(0,1fr)] max-md:grid-rows-[minmax(0,1fr)_auto] max-md:items-stretch max-md:gap-y-4'
                  : 'grid-cols-[var(--art)]'
              }`
        }
        data-lyrics={column || undefined}
      >
        <section
          className={
            theater ? 'flex w-[min(720px,100%)] min-w-0 flex-col' : `flex w-(--art) min-w-0 flex-col${column ? ' max-md:order-2 max-md:w-full' : ''}`
          }
          aria-label="Player"
        >
          {/* Shrinks a touch while paused, as Apple's does. Beside a column, a music video plays here at 16:9. */}
          <div
            className={`relative w-(--art) rounded-[10px] transition-[scale] duration-450 ease-[cubic-bezier(0.34,1.3,0.64,1)] motion-reduce:transition-none${
              video ? ' aspect-video overflow-hidden bg-black' : ' aspect-square'
            }${playing ? '' : ' scale-92'}${column ? ' max-md:hidden' : ''}${theater ? ' hidden' : ''}`}
          >
            {nowPlaying.artworkUrl && (
              <>
                <img
                  key={`glow-${nowPlaying.artworkUrl}`}
                  // The radiosity glow: a small copy, blurred and saturated, behind the artwork.
                  className={`${artworkLayer} -z-1 translate-y-2 scale-96 blur-[20px] saturate-200 data-loaded:opacity-40`}
                  src={sizedArtwork(nowPlaying.artworkUrl, 60)}
                  alt=""
                  referrerPolicy="no-referrer"
                  decoding="async"
                  onLoad={(e) => e.currentTarget.setAttribute('data-loaded', '')}
                />
                {/* The cover that was showing stays under the new one until that has loaded, so a track change fades across rather than through nothing. */}
                {previousCover && (
                  <img
                    key={`prev-${previousCover}`}
                    className="absolute inset-0 size-full rounded-[inherit] object-cover"
                    src={sizedArtwork(previousCover, 600)}
                    alt=""
                    referrerPolicy="no-referrer"
                    decoding="async"
                  />
                )}
                <img
                  key={nowPlaying.artworkUrl}
                  className={`${artworkLayer} bg-[#ffffff12] shadow-[0_4px_10px_#0000001a] data-loaded:opacity-100`}
                  src={sizedArtwork(nowPlaying.artworkUrl, 600)}
                  alt=""
                  referrerPolicy="no-referrer"
                  decoding="async"
                  onLoad={(e) => {
                    e.currentTarget.setAttribute('data-loaded', '');
                    coverLoaded();
                  }}
                  onError={coverFailed}
                />
              </>
            )}
            {/* Apple's motion artwork, over the cover once it's playing. */}
            {motion && (
              <MotionArtwork
                key={motion.url}
                motion={motion}
                className="absolute inset-0 size-full rounded-[inherit] object-cover opacity-0 transition-opacity duration-600 ease-in data-ready:opacity-100"
                playing={playing}
              />
            )}
            {video && !theater && <VideoSurface className="absolute inset-0" />}
          </div>

          <div className={`flex items-center gap-3${theater ? '' : ' mt-[clamp(14px,2.4vh,24px)]'}${column ? ' max-md:mt-0' : ''}`}>
            {/* As the mini player's: the title opens the album, each credited name its artist. */}
            <div className="flex min-w-0 flex-1 flex-col gap-0.5">
              <div className="flex min-w-0 items-center">
                <PageLink
                  className={`min-w-0 max-w-full self-start truncate type-[600_17px/22px] ${pageLink}`}
                  href={albumHref(album?.id ?? null)}
                  resolve={() => albumPageFor(videoId, album, [title, artist].filter(Boolean).join(' '))}
                >
                  {title}
                </PageLink>
                {track?.explicit && <ExplicitBadge size={14} />}
              </div>
              <span className="truncate type-title-3 text-secondary">
                {artist && <Credits videoId={videoId} line={artist} artists={track?.artists ?? []} className={pageLink} />}
                {artist && album?.name && ' — '}
                {album?.name && (
                  <PageLink
                    className={pageLink}
                    href={albumHref(album.id ?? null)}
                    resolve={() => albumPageFor(videoId, album, [title, artist].filter(Boolean).join(' '))}
                  >
                    {album.name}
                  </PageLink>
                )}
              </span>
              <NerdStats />
            </div>
            <FavoriteButton
              videoId={nowPlaying.videoId}
              className="grid size-8 flex-none place-items-center rounded-full bg-quinary text-primary hover:bg-quaternary"
              size={18}
            />
            {track && (
              <TrackMenu
                track={track}
                className="grid size-8 flex-none place-items-center rounded-full bg-quinary text-primary hover:bg-quaternary"
                placement="above-end"
              />
            )}
          </div>

          <Scrubber />
          <Transport />
        </section>

        {showLyrics && (
          <section className="flex min-h-0 animate-fade-in self-stretch" aria-label="Lyrics">
            <LyricsPanel variant="full" />
          </section>
        )}
        {showQueue && (
          // Up Next in the right-hand column: the side panel's list, laid out as a column.
          <section
            className="flex min-h-0 animate-fade-in flex-col self-stretch [mask-image:linear-gradient(180deg,#000_calc(100%-80px),transparent)]"
            aria-label="Up Next"
          >
            <UpNext />
          </section>
        )}
      </div>
      )}

      {!dj && (
      <button
        className={`${corner} bottom-5 left-5 aria-pressed:text-primary`}
        aria-label="Lyrics"
        aria-pressed={showLyrics}
        onClick={() => togglePanel('lyrics')}
      >
        {showLyrics ? <LyricsActiveIcon size={18} /> : <LyricsIcon size={18} />}
      </button>
      )}
      {/* Up Next, with the speaker beside it: the level slides in over Up Next, as it does in the pill. */}
      <div
        className={`group/actions absolute right-5 bottom-5 z-1 flex items-center [--volume-gap:36px] transition-opacity duration-300${hidden}`}
        data-volume-open={volumeOpen || undefined}
        onPointerLeave={() => setVolumeOpen(false)}
        onBlur={(event) => {
          if (!event.currentTarget.contains(event.relatedTarget as Node | null)) setVolumeOpen(false);
        }}
      >
        {/* On the DJ's screen its button takes Up Next's place, as on the phone (there is no queue to show while it speaks);
            when a song plays the DJ's chrome is gone and this is Up Next, as for any track. */}
        {dj ? (
          <DjButton className={`${chromeButton} ${fadeForVolume} aria-expanded:text-primary`} />
        ) : (
          <button
            className={`${chromeButton} ${fadeForVolume} aria-pressed:text-primary`}
            aria-label="Up Next"
            aria-pressed={showQueue}
            onClick={() => togglePanel('queue')}
          >
            <UpNextIcon size={18} />
          </button>
        )}
        <DevicePicker className={`${chromeButton} ${fadeForVolume}`} />
        <VolumeControl open={volumeOpen} onOpen={() => setVolumeOpen(true)} className={chromeButton} iconSize={18} />
      </div>
    </div>
  );
}
