import { GRID, type Rgb } from '@/lib/meshPalette';
import type { DjPhase } from '../session';

/**
 * When the DJ's own screen is up, and the mesh it stands on: a port of Android's `djScreen` (MainActivity),
 * DjMeshPalettes (DjVisuals.kt) and cyclingMeshPalette / Color.tuned (MeshGradient.kt), kept pure to be tested.
 */

export interface DjScreenInput {
  phase: DjPhase;
  /** The DJ is choosing: a request is in and its first words are not yet. */
  pending: boolean;
  /**
   * What is playing now is one of the DJ's clips (a voice): true; a song: false; null when nothing has loaded yet (the DJ is
   * on and its first line is on its way, so the DJ's screen holds the place rather than flashing an empty player).
   */
  clipPlaying: boolean | null;
  /** The DJ's menu is open: the screen stays while the listener types an ask, even if a song starts under it. */
  menuOpen?: boolean;
}

/**
 * Android: `djRunning.pending || (djRunning.running && displayedSong.isDjClip)`. The DJ's screen is up while it is
 * choosing and while its voice speaks; the moment a song of the set starts it is gone, leaving the usual player, and it
 * comes back with the next voice. Not for a DJ that is off, whatever clip may be left in the queue.
 */
export function isDjScreen({ phase, pending, clipPlaying, menuOpen = false }: DjScreenInput): boolean {
  return pending || (phase === 'on' && (clipPlaying !== false || menuOpen));
}

/** Each palette is held this long, and the fade into it takes as long, so one fade runs into the next (MESH_CYCLE_MILLIS). */
export const MESH_CYCLE_MS = 3600;
/**
 * Android turns the DJ's mesh a full turn every 24 s (driftMillis 6000 x 4); the web mesh's blobs move at about a third of
 * that speed on their own, so the DJ's runs this much faster to look the same.
 */
export const DJ_MESH_PACE = 3;

const hex = (v: number): Rgb => [(v >> 16) & 255, (v >> 8) & 255, v & 255];

/** The DJ's colours for the mesh (DjMeshPalettes): soft hues the mesh then lifts into something rich. One after another. */
export const DJ_MESH_PALETTES: readonly (readonly Rgb[])[] = [
  [0xff8fc4, 0xffb27a, 0xa98bff, 0x6fc8ff].map(hex),
  [0xa98bff, 0x6fc8ff, 0x6fe3c0, 0xff8fc4].map(hex),
  [0x6fe3c0, 0xffd86b, 0xff8fa9, 0xd28bff].map(hex),
  [0x6fc8ff, 0x8fa6ff, 0xff7fb8, 0xffb27a].map(hex),
];

function toHsl([r, g, b]: Rgb): [number, number, number] {
  const [rf, gf, bf] = [r / 255, g / 255, b / 255];
  const max = Math.max(rf, gf, bf);
  const min = Math.min(rf, gf, bf);
  const l = (max + min) / 2;
  const d = max - min;
  if (d === 0) return [0, 0, l];
  const s = d / (1 - Math.abs(2 * l - 1));
  const h = max === rf ? ((gf - bf) / d + (gf < bf ? 6 : 0)) : max === gf ? (bf - rf) / d + 2 : (rf - gf) / d + 4;
  return [h * 60, s, l];
}

function fromHsl([h, s, l]: [number, number, number]): Rgb {
  const c = (1 - Math.abs(2 * l - 1)) * s;
  const x = c * (1 - Math.abs(((h / 60) % 2) - 1));
  const m = l - c / 2;
  const [r, g, b] = h < 60 ? [c, x, 0] : h < 120 ? [x, c, 0] : h < 180 ? [0, c, x] : h < 240 ? [0, x, c] : h < 300 ? [x, 0, c] : [c, 0, x];
  return [r, g, b].map((v) => Math.round((v + m) * 255)) as Rgb;
}

/** Boost saturation and clamp lightness so any colour yields a rich, non-muddy mesh (Android's Color.tuned()). */
export function tuned(color: Rgb): Rgb {
  const [h, s, l] = toHsl(color);
  return fromHsl([h, Math.min(1, s * 1.35), Math.min(0.58, Math.max(0.28, l))]);
}

/**
 * Android's mesh has four blobs at the corners, one per colour (anchors (.2,.25) (.8,.2) (.75,.8) (.25,.75)); the web's has
 * GRID x GRID cells, so every cell takes the colour of the corner it lies in. Cells are laid out as MeshBackdrop's BLOBS
 * are: a cell's x is GRID - 1 - row and its y is its column.
 */
export function paletteCells(palette: readonly Rgb[]): Rgb[] {
  const colors = palette.slice(0, 4).map(tuned);
  const half = GRID / 2;
  return Array.from({ length: GRID * GRID }, (_, i) => {
    const right = GRID - 1 - Math.floor(i / GRID) >= half;
    const bottom = i % GRID >= half;
    const slot = bottom ? (right ? 2 : 3) : right ? 1 : 0;
    return colors[slot % colors.length]!;
  });
}

/** The palette showing after [step] steps: the first for ever with "reduce animation" (a step would only be a snap). */
export function paletteAt(step: number, reduceMotion: boolean): readonly Rgb[] {
  return DJ_MESH_PALETTES[reduceMotion ? 0 : step % DJ_MESH_PALETTES.length]!;
}
