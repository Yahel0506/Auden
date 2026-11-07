import { AudioLines } from 'lucide-react';
import { BackwardIcon, ForwardIcon, PauseIcon, PlayIcon } from '@/components/icons/Transport';
import { usePlayer } from '@/state/player';
import { useDj } from '../session';
import { DjRing } from './DjRing';

/** Android's dj_loading / dj_no_words. */
export const DJ_LOADING = 'Choosing your set…';
export const DJ_NO_WORDS = 'The DJ is speaking';

/**
 * Android's DjMeshPalettes sit under the mesh's own scrim (MeshGradient.kt): 0.10 black at the top to 0.38 at the foot, so
 * white words stay legible over the pastel.
 */
export const DJ_SCRIM = 'linear-gradient(to bottom, rgb(0 0 0 / 0.10), rgb(0 0 0 / 0.38))';

const transportButton =
  'grid place-items-center rounded-full transition-[background-color,scale] duration-100 ease-in enabled:hover:bg-quinary enabled:active:scale-90 disabled:opacity-40';

/**
 * The player while the DJ speaks or chooses (Android DjNowPlayingScreen): the DJ's name, one ring that moves with the
 * voice, what the set is about, what is said (written, so nothing depends on hearing it), the transport (back has nothing
 * to step to, so it rests) and, bottom right, the DJ's button (the player's chrome adds it). The mesh behind it is the
 * player's own, which FullScreenPlayer swaps to the DJ's palettes while this is up. It is gone the moment a song plays.
 */
export function DjScreen() {
  const line = useDj((s) => s.line);
  const notice = useDj((s) => s.notice);
  const theme = useDj((s) => s.theme);
  const request = useDj((s) => s.request);
  const loading = useDj((s) => s.pending);
  const playing = usePlayer((s) => s.status === 'playing' || s.status === 'buffering');
  const hasTrack = usePlayer((s) => s.nowPlaying !== null);
  const { togglePlay, next } = usePlayer.getState();
  // While it chooses, what was asked for, not what the last set was about.
  const label = loading ? request : theme;

  return (
    <div className="pointer-events-none absolute inset-0 z-0 flex flex-col items-center px-6 pt-[clamp(20px,5vh,40px)] pb-16 text-white" data-dj-screen>
      <div className="flex items-center gap-2 text-white/80">
        <AudioLines size={18} aria-hidden="true" />
        <span className="type-[700_15px/20px]">Auden DJ</span>
      </div>
      <div className="relative mt-[clamp(8px,2vh,18px)] aspect-square shrink-0 w-[min(88vw,calc(100dvh-360px),560px)] max-md:w-[min(88vw,calc(100dvh-340px),420px)]">
        <DjRing className="absolute inset-0 size-full" />
      </div>
      <div className="mt-1.5 flex w-[min(640px,100%)] flex-col items-center text-center" role="status" aria-live="polite">
        {label && <p className="mb-2 max-w-full truncate type-[700_14px/18px] tracking-[0.6px] text-white/62">{label}</p>}
        <p className={`max-w-full line-clamp-5 type-[600_19px/26px] text-white/94${loading ? ' animate-dj-loading motion-reduce:animate-none' : ''}`}>
          {loading ? DJ_LOADING : (line ?? DJ_NO_WORDS)}
        </p>
        {notice && <p className="mt-2 max-w-full line-clamp-2 type-callout text-white/50">{notice}</p>}
      </div>
      <div className="flex-1" />
      <div className="pointer-events-auto flex h-14 w-[min(100%,260px)] items-center justify-between">
        <button className={`${transportButton} size-12 text-white`} disabled aria-label="Previous">
          <BackwardIcon size={40} />
        </button>
        <button
          className={`${transportButton} size-14 text-white`}
          disabled={!hasTrack}
          aria-label={playing ? 'Pause' : 'Play'}
          onClick={togglePlay}
        >
          {playing ? <PauseIcon size={48} /> : <PlayIcon size={48} />}
        </button>
        <button className={`${transportButton} size-12 text-white`} disabled={!hasTrack || loading} aria-label="Next" onClick={next}>
          <ForwardIcon size={40} />
        </button>
      </div>
    </div>
  );
}
