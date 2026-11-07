import { describe, expect, it } from 'vitest';
import { ECHOES, FALL, POINTS, RISE, echoDrift, geometry, levelAt, ringPoints, smooth, talkingLevel, voiceTarget, wobbleAt } from './ringShape';

describe('levelAt', () => {
  it('averages the bins from one before the playhead to two after it, and is 0 without a voice', () => {
    const env = new Float32Array([0.1, 0.5, 0.9, 0.3, 0.7, 0.2]);
    // at = floor(0.5 * 6) = 3: bins 2..5.
    expect(levelAt(env, 500, 1000)).toBeCloseTo((0.9 + 0.3 + 0.7 + 0.2) / 4);
    // at the start the window is clipped to the bins that exist: 0..2.
    expect(levelAt(env, 0, 1000)).toBeCloseTo((0.1 + 0.5 + 0.9) / 3);
    // past the end: the last bin, with its neighbour before it.
    expect(levelAt(env, 5000, 1000)).toBeCloseTo((0.7 + 0.2) / 2);
    expect(levelAt(null, 100, 1000)).toBe(0);
    expect(levelAt(env, 100, 0)).toBe(0);
  });
});

describe('voiceTarget', () => {
  const env = new Float32Array([0.6, 0.6, 0.6, 0.6]);
  const base = { loading: false, playing: true, envelope: env, positionMs: 100, durationMs: 1000, t: 0 };
  it('breathes between 0.06 and 0.22 while it chooses, whatever the clip says', () => {
    expect(voiceTarget({ ...base, loading: true, t: 0 })).toBeCloseTo(0.14);
    const peak = Math.PI / 2 / 2.2;
    expect(voiceTarget({ ...base, loading: true, t: peak, playing: false })).toBeCloseTo(0.22);
    expect(voiceTarget({ ...base, loading: true, t: 3 * peak })).toBeCloseTo(0.06);
  });
  it('rests when paused or with nothing to follow, and follows the envelope otherwise', () => {
    expect(voiceTarget({ ...base, playing: false })).toBe(0);
    expect(voiceTarget({ ...base, envelope: new Float32Array(0) })).toBe(0);
    expect(voiceTarget({ ...base, durationMs: 0 })).toBe(0);
    expect(voiceTarget(base)).toBeCloseTo(0.6);
  });
});

describe('smooth', () => {
  it('closes 0.4 of the gap rising and 0.14 falling in one 60 Hz frame, as Android does', () => {
    expect(smooth(0, 1, 1000 / 60)).toBeCloseTo(RISE);
    expect(1 - smooth(1, 0, 1000 / 60)).toBeCloseTo(FALL);
    expect(RISE).toBe(0.4);
    expect(FALL).toBe(0.14);
  });
  it('reaches the same place whatever the frame rate, and never overshoots', () => {
    let a = 0;
    for (let i = 0; i < 6; i++) a = smooth(a, 1, 1000 / 60);
    let b = 0;
    for (let i = 0; i < 3; i++) b = smooth(b, 1, 1000 / 30);
    expect(a).toBeCloseTo(b);
    expect(smooth(0.5, 0.5, 16)).toBeCloseTo(0.5);
    expect(smooth(0, 1, 5000)).toBeLessThanOrEqual(1);
  });
});

describe('geometry', () => {
  it('is a ring 0.30 of the side wide and 0.062 thick, pushed further out of round by the voice', () => {
    const g = geometry(400, 0, 0);
    expect(g.radius).toBeCloseTo(120);
    expect(g.stroke).toBeCloseTo(24.8);
    expect(g.push).toBeCloseTo(0.035);
    const loud = geometry(400, 0, 1);
    expect(loud.radius).toBeCloseTo(120 * 1.03);
    expect(loud.push).toBeCloseTo(0.225);
  });
  it('breathes with the clock at rest', () => {
    const t = Math.PI / 2 / 1.4;
    expect(geometry(400, t, 0).radius).toBeCloseTo(120 * 1.012);
  });
});

describe('the ring', () => {
  it('has POINTS + 1 points that close the loop', () => {
    const pts = ringPoints(100, 100, { radius: 50, push: 0.1 }, 0);
    expect(POINTS).toBe(140);
    expect(pts).toHaveLength(141);
    expect(pts[0]![0]).toBeCloseTo(pts[140]![0]);
    expect(pts[0]![1]).toBeCloseTo(pts[140]![1]);
  });
  it('is round with no push and deeper with the voice', () => {
    const spread = (push: number) => {
      const r = ringPoints(0, 0, { radius: 100, push }, 1.3).map(([x, y]) => Math.hypot(x, y));
      return Math.max(...r) - Math.min(...r);
    };
    expect(spread(0)).toBeCloseTo(0);
    expect(spread(0.225)).toBeGreaterThan(spread(0.035) * 5);
  });
  it('wobbles with time (the lobes move) and never repeats exactly', () => {
    expect(wobbleAt(1, 0)).not.toBeCloseTo(wobbleAt(1, 1));
    expect(wobbleAt(0, 0)).toBeCloseTo(0.55 * Math.sin(0.3) + 0.32 * Math.sin(1.1) + 0.22 * Math.sin(2.0));
  });
});

describe('echoes', () => {
  it('are the three Android has, the nearest the strongest', () => {
    expect(ECHOES.map((e) => [e.lag, e.color, e.alpha])).toEqual([
      [0.34, '#6EC8FF', 0.42],
      [0.2, '#FF8FC4', 0.52],
      [0.09, '#6FE3C0', 0.62],
    ]);
  });
  it('drift off to the side Android says, further with the voice and in proportion to their lag', () => {
    const [x, y] = echoDrift(400, 2, 0.5, 0.34);
    const k = 400 * 0.045 * 0.9;
    expect(x).toBeCloseTo(Math.cos(2 * 0.7 + 0.34 * 3) * k, 6);
    expect(y).toBeCloseTo(Math.sin(2 * 0.55 + 0.34 * 5) * k, 6);
    const [nx] = echoDrift(400, 2, 0.5, 0.09);
    expect(nx).toBeCloseTo(Math.cos(2 * 0.7 + 0.09 * 3) * k * (0.09 / 0.34), 6);
    expect(Math.hypot(...echoDrift(400, 0, 1, 0.34))).toBeGreaterThan(Math.hypot(...echoDrift(400, 0, 0, 0.34)));
  });
});

describe('talkingLevel', () => {
  it('bursts and pauses inside 0.14..0.74', () => {
    const levels = Array.from({ length: 400 }, (_, i) => talkingLevel(i * 0.05));
    expect(Math.min(...levels)).toBeGreaterThanOrEqual(0.14 - 1e-9);
    expect(Math.max(...levels)).toBeLessThanOrEqual(0.74 + 1e-9);
    expect(Math.max(...levels)).toBeGreaterThan(0.5);
    expect(Math.min(...levels)).toBeLessThan(0.2);
  });
});
