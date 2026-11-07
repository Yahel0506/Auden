import { describe, expect, it } from 'vitest';
import { DETAIL_FADE_FRACTION, HERO_FADE_FRACTION, heroAlpha, heroFadeMask, heroFadeOverlay } from './heroFade';

describe('heroAlpha', () => {
  it('runs from opaque to clear along smootherstep', () => {
    expect(heroAlpha(0)).toBe(1);
    expect(heroAlpha(1)).toBe(0);
    expect(heroAlpha(0.5)).toBeCloseTo(0.5, 10);
    // flat at both ends: the start of the dissolve is not a visible line
    expect(1 - heroAlpha(0.05)).toBeLessThan(0.01);
    expect(heroAlpha(0.95)).toBeLessThan(0.01);
  });
  it('clamps outside the dissolve', () => {
    expect(heroAlpha(-1)).toBe(1);
    expect(heroAlpha(2)).toBe(0);
  });
});

describe('heroFadeMask', () => {
  it('has 17 stops (16 steps) that begin where the dissolve begins', () => {
    const mask = heroFadeMask();
    expect(mask.match(/rgb\(/g)).toHaveLength(17);
    expect(mask).toContain(`rgb(0 0 0 / 1) ${(1 - HERO_FADE_FRACTION) * 100}%`);
    expect(mask).toContain('rgb(0 0 0 / 0) 100%');
  });
  it('is gentler than the old 0.62 and tighter for a detail cover', () => {
    expect(HERO_FADE_FRACTION).toBe(0.52);
    expect(DETAIL_FADE_FRACTION).toBe(0.42);
    expect(heroFadeMask(DETAIL_FADE_FRACTION)).toContain('rgb(0 0 0 / 1) 58%');
  });
  it('alpha only ever falls', () => {
    const alphas = [...heroFadeMask().matchAll(/rgb\(0 0 0 \/ ([\d.]+)\)/g)].map((m) => Number(m[1]));
    for (let i = 1; i < alphas.length; i++) expect(alphas[i]!).toBeLessThanOrEqual(alphas[i - 1]!);
  });
});

describe('heroFadeOverlay', () => {
  it('goes from clear to the page colour over the bottom 52%, 17 stops', () => {
    const css = heroFadeOverlay('var(--c)');
    expect(css.match(/color-mix/g)).toHaveLength(17);
    expect(css).toContain('color-mix(in srgb, var(--c) 0%, transparent) 48%');
    expect(css).toContain('color-mix(in srgb, var(--c) 100%, transparent) 100%');
  });
});
