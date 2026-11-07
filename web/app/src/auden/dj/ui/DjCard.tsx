import { useEffect, useRef, useState } from 'react';
import { useParty } from '@/party/listenTogether';
import { MeshBackdrop } from '@/shell/MeshBackdrop';
import { useUi } from '@/state/ui';
import { loadDjPool } from '../loadPool';
import { startDj, useDj } from '../session';
import { DjButton } from './DjMenu';
import { DJ_MESH_PACE, MESH_CYCLE_MS } from './djScreen';
import { DjRing } from './DjRing';
import { DJ_SCRIM } from './DjScreen';
import { useDjMeshColors } from './useDjScreen';

/** Whether [ref] is on screen: the card's mesh and ring hold still while it is scrolled away (Android: held still while the feed scrolls). */
function useOnScreen<T extends Element>(ref: React.RefObject<T | null>): boolean {
  const [seen, setSeen] = useState(true);
  useEffect(() => {
    const el = ref.current;
    if (!el || typeof IntersectionObserver !== 'function') return;
    const observer = new IntersectionObserver((entries) => setSeen(entries[entries.length - 1]?.isIntersecting ?? true));
    observer.observe(el);
    return () => observer.disconnect();
  }, [ref]);
  return seen;
}

/**
 * The DJ's card at the top of Home (Android HomeScreen DjCard): a big card on the DJ's moving mesh, the same palettes
 * cycling, with the ring speaking on the right. The only place the DJ starts from, straight into its own screen. The mesh
 * and ring hold still while the card is off screen or the full-screen player covers it.
 */
export function DjCard() {
  const phase = useDj((s) => s.phase);
  const notice = useDj((s) => s.notice);
  const inParty = useParty((s) => !!s.code);
  const setFullScreen = useUi((s) => s.setFullScreen);
  const covered = useUi((s) => s.fullScreen);
  const [busy, setBusy] = useState(false);
  const card = useRef<HTMLElement>(null);
  const seen = useOnScreen(card);
  const motion = seen && !covered;
  const colors = useDjMeshColors(motion);
  const on = phase === 'on';
  return (
    <section
      ref={card}
      className="relative isolate mx-(--body-gutter) mt-6 flex items-center gap-6 overflow-hidden rounded-[26px] bg-[#343438] py-5 pr-2 pl-[22px] text-white"
      aria-label="Auden DJ"
    >
      <MeshBackdrop artworkUrl={null} playing={motion} colors={colors} fadeMs={MESH_CYCLE_MS} pace={DJ_MESH_PACE} />
      <div className="pointer-events-none absolute inset-0 -z-1" style={{ background: DJ_SCRIM }} aria-hidden="true" />
      <div className="min-w-0 flex-1">
        <h2 className="type-title-2-emphasized">Auden DJ</h2>
        <p className="mt-1.5 type-body-tall text-white/85">
          {inParty ? 'The DJ can’t play in a Listen Together party: everyone hears the same stream.' : 'Themed sets from your own music, with a voice in between'}
        </p>
        {notice && !on && <p className="mt-1 type-callout text-white/90">{notice}</p>}
        <button
          className="mt-4 inline-flex h-10 min-w-[130px] items-center justify-center rounded-pill bg-white px-5 type-body-emphasized text-black disabled:opacity-50"
          disabled={busy || inParty || phase === 'starting'}
          onClick={() => {
            if (on) return setFullScreen(true);
            setBusy(true);
            // Straight into the DJ's screen, which holds the wait ("Choosing your set…"); it goes again if the DJ cannot start.
            setFullScreen(true);
            void startDj(loadDjPool).then((r) => {
              setBusy(false);
              if (!r.ok && r.reason !== 'busy') setFullScreen(false);
            });
          }}
        >
          {on ? 'Open DJ' : phase === 'starting' || busy ? 'Starting…' : 'Listen'}
        </button>
        {/* While the DJ is on, its menu (ask, more / less like this, stop) is here too: during songs the player carries none of the DJ's
            chrome, and a DJ without a voice never shows its own screen. */}
        {on && <DjButton className="mt-4 ml-2 inline-grid size-10 place-items-center rounded-full bg-white/20 align-top text-white hover:bg-white/30 aria-expanded:bg-white/30" />}
      </div>
      <DjRing className="size-[136px] shrink-0 max-sm:size-[116px]" running={motion} talking />
    </section>
  );
}
