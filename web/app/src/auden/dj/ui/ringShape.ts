/**
 * The DJ's ring, as numbers (drawn by DjRing): a port of Android's DjVisuals.kt (DjRing, rememberVoiceLevel), kept pure
 * so its behaviour can be tested without a canvas. Every constant below is that file's.
 */

/** The points of the ring: Android walks `0..points`, so the last point closes the loop on the first. */
export const POINTS = 140;

/** One echo: the ring a moment ago (`lag`, seconds), off to one side, in its own colour. Android's `echoes`. */
export interface Echo {
  lag: number;
  color: string;
  alpha: number;
}
export const ECHOES: readonly Echo[] = [
  { lag: 0.34, color: '#6EC8FF', alpha: 0.42 },
  { lag: 0.2, color: '#FF8FC4', alpha: 0.52 },
  { lag: 0.09, color: '#6FE3C0', alpha: 0.62 },
];

/** The ring's sweep of pastel (Brush.sweepGradient), evenly spaced round the ring. */
export const SWEEP = ['#FFFFFF', '#FFD6EC', '#FFFFFF', '#D3E8FF', '#FFFFFF'] as const;
/** The sweep turns this many degrees a second. */
export const SWEEP_DEG_PER_SEC = 22;
/** The soft glow under the ring: white at this alpha, this many times as thick. */
export const GLOW_ALPHA = 0.16;
export const GLOW_WIDTH = 2.1;
/** The echoes are a hair thinner than the ring. */
export const ECHO_WIDTH = 0.96;

export interface Geometry {
  /** The ring's mean radius, px. */
  radius: number;
  /** Its thickness, px. */
  stroke: number;
  /** How far the voice can push it out of round, as a fraction of the radius. */
  push: number;
}

/** Sizes for a square of side [side] at time [t] (seconds) and voice level [l] (0..1). */
export function geometry(side: number, t: number, l: number): Geometry {
  return {
    radius: side * 0.3 * (1 + 0.03 * l + 0.012 * Math.sin(t * 1.4)),
    stroke: side * 0.062,
    push: 0.035 + 0.19 * l,
  };
}

/** The wobble at angle [a] and time [time]: four slow harmonics, so the loop is never a circle and never repeats. */
export function wobbleAt(a: number, time: number): number {
  return (
    0.55 * Math.sin(2 * a + 0.9 * time + 0.3) +
    0.32 * Math.sin(3 * a - 1.3 * time + 1.1) +
    0.22 * Math.sin(4 * a + 1.7 * time + 2.0) +
    0.13 * Math.sin(5 * a - 2.3 * time)
  );
}

/** Fills [out] (2 * (POINTS + 1) numbers, x then y) with the ring at [time] round (cx, cy): no allocation, for the draw loop. */
export function fillRing(out: Float32Array, cx: number, cy: number, { radius, push }: Pick<Geometry, 'radius' | 'push'>, time: number): void {
  for (let i = 0; i <= POINTS; i++) {
    const a = (2 * Math.PI * i) / POINTS;
    const r = radius * (1 + push * wobbleAt(a, time));
    out[2 * i] = cx + r * Math.cos(a);
    out[2 * i + 1] = cy + r * Math.sin(a);
  }
}

/** The ring at [time] as [x, y] pairs (POINTS + 1 of them: the last equals the first), to be closed by the caller. */
export function ringPoints(cx: number, cy: number, geometry: Pick<Geometry, 'radius' | 'push'>, time: number): Array<[number, number]> {
  const buffer = new Float32Array(2 * (POINTS + 1));
  fillRing(buffer, cx, cy, geometry, time);
  return Array.from({ length: POINTS + 1 }, (_, i) => [buffer[2 * i]!, buffer[2 * i + 1]!]);
}

/** Where an echo sits relative to the ring: it drifts off to one side, more with the voice and the more it lags. */
export function echoDrift(side: number, t: number, l: number, lag: number): [number, number] {
  const k = side * 0.045 * (0.4 + l) * (lag / 0.34);
  return [Math.cos(t * 0.7 + lag * 3) * k, Math.sin(t * 0.55 + lag * 5) * k];
}

/**
 * A voice for places with none to follow (the card on Home): bursts and pauses, never a steady wave
 * (Android's `talking`).
 */
export function talkingLevel(t: number): number {
  return Math.min(1, Math.max(0, 0.14 + 0.6 * Math.abs(Math.sin(t * 2.7) * Math.sin(t * 1.1 + 0.6))));
}

/** The loudness at the playhead: the mean of the clip's envelope bins one before to two after it (Android's four-bin window). */
export function levelAt(envelope: Float32Array | null | undefined, positionMs: number, durationMs: number): number {
  if (!envelope?.length || durationMs <= 0) return 0;
  const size = envelope.length;
  const at = Math.min(size - 1, Math.max(0, Math.trunc((positionMs / durationMs) * size)));
  let sum = 0;
  let n = 0;
  for (let i = at - 1; i <= at + 2; i++) {
    if (i >= 0 && i < size) {
      sum += envelope[i]!;
      n++;
    }
  }
  return n ? sum / n : 0;
}

export interface VoiceInput {
  /** The DJ is choosing: it breathes slowly so the wait is not a still face. */
  loading: boolean;
  /** The clip is sounding (not paused). */
  playing: boolean;
  envelope: Float32Array | null | undefined;
  positionMs: number;
  durationMs: number;
  /** The ring's clock, seconds. */
  t: number;
}

/** What the level is heading for this frame (rememberVoiceLevel's `target`). */
export function voiceTarget({ loading, playing, envelope, positionMs, durationMs, t }: VoiceInput): number {
  if (loading) return 0.14 + 0.08 * Math.sin(t * 2.2);
  if (!playing || !envelope?.length || durationMs <= 0) return 0;
  return levelAt(envelope, positionMs, durationMs);
}

/** Android eases once per frame (a 60 Hz frame): this many of each gap closed rising, and falling. */
export const RISE = 0.4;
export const FALL = 0.14;
const FRAME_MS = 1000 / 60;

/** Quick to rise with the voice, gentle to fall away; the per-frame rates, made independent of the frame rate. */
export function smooth(current: number, target: number, dtMs: number): number {
  const rate = target > current ? RISE : FALL;
  const k = 1 - (1 - rate) ** (dtMs / FRAME_MS);
  return current + (target - current) * k;
}
