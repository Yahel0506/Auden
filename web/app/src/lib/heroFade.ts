/**
 * Where a cover dissolves into the background (Android HeroFade.kt): alpha falls from 1 to 0 along Perlin's
 * smootherstep, 1 - t^3 (t (6t - 15) + 10), sampled at [STEPS] stops, instead of a straight ramp whose hard start
 * and end read as a line.
 */
export const HERO_FADE_FRACTION = 0.52;
/** A full-bleed album or playlist cover fades over a smaller share of its height (Android DetailScreen). */
export const DETAIL_FADE_FRACTION = 0.42;
const STEPS = 16;

/** Opacity of the cover [t] of the way through the dissolve (0 = where it starts, 1 = its bottom edge). */
export function heroAlpha(t: number): number {
  const x = Math.min(1, Math.max(0, t));
  return 1 - x * x * x * (x * (6 * x - 15) + 10);
}

/** The CSS mask for a cover whose bottom [fraction] dissolves. Use as `mask-image` (and `-webkit-mask-image`). */
export function heroFadeMask(fraction: number = HERO_FADE_FRACTION): string {
  const start = 1 - fraction;
  const stops = Array.from({ length: STEPS + 1 }, (_, i) => {
    const t = i / STEPS;
    const alpha = Math.round(heroAlpha(t) * 1000) / 1000;
    return `rgb(0 0 0 / ${alpha}) ${Math.round((start + fraction * t) * 10000) / 100}%`;
  });
  return `linear-gradient(to bottom, #000 0%, ${stops.join(', ')})`;
}

/** Inline-style form: both property names, since a stylesheet pass can keep only one prefixed pair. */
export function heroFadeStyle(fraction: number = HERO_FADE_FRACTION): { maskImage: string; WebkitMaskImage: string } {
  const mask = heroFadeMask(fraction);
  return { maskImage: mask, WebkitMaskImage: mask };
}

/**
 * The same dissolve as an overlay in [color] (a banner that melts into the page's colour, which a mask cannot do over a
 * photo and a page tint at once): transparent where the dissolve begins, [color] at the bottom edge, along the same curve.
 */
export function heroFadeOverlay(color: string, fraction: number = HERO_FADE_FRACTION): string {
  const start = 1 - fraction;
  const stops = Array.from({ length: STEPS + 1 }, (_, i) => {
    const t = i / STEPS;
    const cover = Math.round((1 - heroAlpha(t)) * 1000) / 10;
    return `color-mix(in srgb, ${color} ${cover}%, transparent) ${Math.round((start + fraction * t) * 10000) / 100}%`;
  });
  return `linear-gradient(to bottom, transparent 0%, ${stops.join(', ')})`;
}
