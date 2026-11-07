/**
 * Easing the incoming song back to its own speed (Android CrossfadeController.beginEase, v1.12).
 *
 * A song that enters stretched to match the outgoing tempo has to come back to rate 1. Its beat only has to match
 * while the old song is clearly audible, so the walk back starts at [EASE_BACK_FROM] of the blend, with the old song
 * still fading out, instead of seconds after it has gone. It moves a beat at a time, and takes as few beats as fit in
 * what is left of the blend, at no more than [EASE_MAX_STEP] a beat; if that is not enough it runs on past the end.
 */
export const EASE_BACK_FROM = 0.7;
export const EASE_MAX_STEP = 0.02;
/** One step per beat; with no beat length (no grid), this. */
export const EASE_FALLBACK_STEP_SEC = 0.5;
/** Within this of rate 1 the song just goes to 1. */
export const EASE_DONE = 0.002;
/** The unhurried walk (Android rampStepsFor): at most this much per beat. */
const GENTLE_STEP = 0.0075;

export interface EaseStep {
  /** Seconds after the blend's start. */
  at: number;
  rate: number;
}

/**
 * The rate changes that bring [rate] back to 1 for a blend lasting [blendSec] output seconds, with a beat of [beatSec]
 * (null = unknown). Empty when [rate] already is 1.
 */
export function easeBackSteps(rate: number, blendSec: number, beatSec: number | null): EaseStep[] {
  const stretch = rate - 1;
  const from = blendSec * EASE_BACK_FROM;
  if (Math.abs(stretch) < EASE_DONE) return Math.abs(stretch) === 0 ? [] : [{ at: from, rate: 1 }];
  const stepSec = beatSec && beatSec > 0 ? beatSec : EASE_FALLBACK_STEP_SEC;
  const gentle = Math.ceil(Math.abs(stretch) / GENTLE_STEP);
  const fits = Math.floor((blendSec - from) / stepSec);
  const steps = Math.max(Math.ceil(Math.abs(stretch) / EASE_MAX_STEP), Math.min(gentle, fits), 1);
  return Array.from({ length: steps }, (_, i) => ({ at: from + (i + 1) * stepSec, rate: rate - (stretch * (i + 1)) / steps }));
}
