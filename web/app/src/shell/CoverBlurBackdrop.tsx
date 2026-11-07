import { useEffect, useState } from 'react';
import { blurredCover } from '@/lib/coverBlur';

/** The crossfade between one track's picture and the next (the phone's still crossfade is 320 ms). */
export const BLUR_FADE_MS = 320;
/** How long the scrim takes to deepen or lift when the lyrics or the queue open or close. */
export const SCRIM_MS = 360;
/** Scrim alphas at the top, 55% and the bottom (the phone's 0.34 / 0.48 / 0.64), at full strength. */
const SCRIM = 'linear-gradient(to bottom, rgb(0 0 0 / 0.34), rgb(0 0 0 / 0.48) 55%, rgb(0 0 0 / 0.64))';
/**
 * The main view's scrim, as a share of that. The phone's is 0.6 over an uncapped blurred cover; the web's mesh
 * is already held to a luminance of 0.38 for white text (lib/meshPalette.ts), so the same scrim on top would
 * darken it twice and muddy the colours. A quarter keeps the intent (lighter than beside lyrics or the queue)
 * without that.
 */
export const MAIN_SCRIM = 0.25;

interface Layer {
  id: number;
  src: string;
}

/**
 * The full-cover blurred artwork behind the player's lyrics and queue, under a scrim that deepens when
 * they open. A new track's picture is made first and only then crossfaded in over the one showing, so the
 * backdrop never dips to black, and a cover that cannot be read leaves the picture on screen as it is.
 * Hidden (but kept, so reopening does not blink) while the main view stands on the mesh alone.
 */
export function CoverBlurBackdrop({ artworkUrl, column, solid = false }: { artworkUrl: string | null; column: boolean; solid?: boolean }) {
  // `solid`: the Blur theme has no mesh underneath, so this layer is the player's whole floor: opaque from the first frame, with or without a picture (a local file with no art, a cover that cannot be read, the moment before the blur is made).
  // `column` = shown: the lyrics or queue column is open, or the Blur theme stands on this cover in the main view too.
  const [layers, setLayers] = useState<Layer[]>([]);
  const [shown, setShown] = useState<number | null>(null);

  useEffect(() => {
    if (!artworkUrl) return;
    let live = true;
    blurredCover(artworkUrl)
      .then((src) => {
        if (!live) return;
        setLayers((old) => (old[old.length - 1]?.src === src ? old : [...old.slice(-1), { id: (old[old.length - 1]?.id ?? 0) + 1, src }]));
      })
      .catch(() => {
        // Unreadable: keep what is showing.
      });
    return () => {
      live = false;
    };
  }, [artworkUrl]);

  // A layer fades in on the frame after it mounts (so the transition runs); the one beneath goes once it has covered it.
  const newest = layers[layers.length - 1];
  useEffect(() => {
    if (!newest) return;
    const frame = requestAnimationFrame(() => setShown(newest.id));
    const done = setTimeout(() => setLayers((old) => old.filter((l) => l.id === newest.id)), BLUR_FADE_MS + 60);
    return () => {
      cancelAnimationFrame(frame);
      clearTimeout(done);
    };
  }, [newest]);

  return (
    <div
      className="pointer-events-none absolute inset-0 -z-1 overflow-hidden bg-black transition-opacity ease-[cubic-bezier(0.4,0,0.2,1)] motion-reduce:transition-none"
      style={{ opacity: column && (solid || layers.length > 0) ? 1 : 0, transitionDuration: `${SCRIM_MS}ms` }}
      aria-hidden="true"
    >
      {layers.map((layer, index) => (
        <img
          key={layer.id}
          className="absolute inset-0 size-full scale-110 object-cover transition-opacity motion-reduce:transition-none"
          // The first picture shows at once; later ones fade in over the one beneath.
          style={{ opacity: index === 0 || shown === layer.id ? 1 : 0, transitionDuration: `${BLUR_FADE_MS}ms` }}
          src={layer.src}
          alt=""
          decoding="async"
        />
      ))}
      <div className="absolute inset-0" style={{ background: SCRIM }} />
    </div>
  );
}

/**
 * Continuation's scrim: Android's 0.04 at the top to 0.58 at the foot, linear, so the buttons keep their contrast and
 * the head stays bright. The web mesh is already held to a luminance of 0.38 (lib/meshPalette.ts), which Android's is
 * not, so the foot is 0.46 here (checked on the dev server) rather than darkening it twice.
 */
export const CONTINUATION_SCRIM = 'linear-gradient(to bottom, rgb(0 0 0 / 0.04), rgb(0 0 0 / 0.46))';

/** The scrim over the mesh in the main view: lighter than beside the lyrics, and deepening with them. */
export function MeshScrim({ column, continuation = false }: { column: boolean; continuation?: boolean }) {
  return (
    <div
      className="pointer-events-none absolute inset-0 -z-1 transition-opacity ease-[cubic-bezier(0.4,0,0.2,1)] motion-reduce:transition-none"
      style={{
        background: continuation ? CONTINUATION_SCRIM : SCRIM,
        opacity: column ? 0 : continuation ? 1 : MAIN_SCRIM,
        transitionDuration: `${SCRIM_MS}ms`,
      }}
      aria-hidden="true"
    />
  );
}
