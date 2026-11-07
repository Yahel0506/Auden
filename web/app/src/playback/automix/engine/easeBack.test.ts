import { describe, expect, it } from 'vitest';
import { EASE_BACK_FROM, EASE_MAX_STEP, easeBackSteps } from './easeBack';

describe('easeBackSteps', () => {
  it('starts inside the blend, while the old song is still fading out, and ends at 1', () => {
    const steps = easeBackSteps(1.08, 32, 0.5);
    expect(steps[0]!.at).toBeGreaterThan(32 * EASE_BACK_FROM);
    expect(steps.at(-1)!.rate).toBeCloseTo(1, 12);
    // all of it inside the blend when it fits (1.08: 11 beats of 0.5 s in the last 9.6 s)
    expect(steps.at(-1)!.at).toBeLessThanOrEqual(32);
  });

  it('moves one beat at a time, falling toward 1 without overshooting', () => {
    const steps = easeBackSteps(1.08, 32, 0.5);
    for (let i = 1; i < steps.length; i++) {
      expect(steps[i]!.at - steps[i - 1]!.at).toBeCloseTo(0.5, 12);
      expect(steps[i]!.rate).toBeLessThan(steps[i - 1]!.rate);
    }
    expect(steps.every((s) => s.rate >= 1)).toBe(true);
  });

  it('walks back up from a slowed song too', () => {
    const steps = easeBackSteps(0.93, 32, 0.5);
    expect(steps.at(-1)!.rate).toBeCloseTo(1, 12);
    expect(steps.every((s) => s.rate <= 1)).toBe(true);
  });

  it('never moves more than EASE_MAX_STEP in a beat, even in a short blend (it runs on past the end instead)', () => {
    const steps = easeBackSteps(1.2, 8, 0.5);
    let prev = 1.2;
    for (const s of steps) {
      expect(prev - s.rate).toBeLessThanOrEqual(EASE_MAX_STEP + 1e-12);
      prev = s.rate;
    }
    expect(steps.at(-1)!.at).toBeGreaterThan(8);
    expect(steps).toHaveLength(10);
  });

  it('steps every half second without a beat length', () => {
    const steps = easeBackSteps(1.05, 40, null);
    expect(steps[1]!.at - steps[0]!.at).toBeCloseTo(0.5, 12);
  });

  it('goes straight to 1 when almost there, and does nothing when already there', () => {
    expect(easeBackSteps(1.001, 32, 0.5)).toEqual([{ at: 32 * EASE_BACK_FROM, rate: 1 }]);
    expect(easeBackSteps(1, 32, 0.5)).toEqual([]);
  });
});
