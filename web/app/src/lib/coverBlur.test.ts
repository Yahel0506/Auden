import { describe, expect, it } from 'vitest';
import { boxBlur, boxBlurPass } from './coverBlur';

const solid = (w: number, h: number, rgba: [number, number, number, number]) => {
  const data = new Uint8ClampedArray(w * h * 4);
  for (let i = 0; i < w * h; i++) data.set(rgba, i * 4);
  return data;
};

describe('boxBlur', () => {
  it('leaves a flat picture as it was', () => {
    const data = solid(12, 12, [40, 90, 200, 255]);
    expect([...boxBlur(data, 12, 12, 3, 2)]).toEqual([...data]);
  });

  it('does not touch what it is given', () => {
    const data = solid(8, 8, [0, 0, 0, 255]);
    data.set([255, 255, 255, 255], (4 * 8 + 4) * 4);
    const copy = new Uint8ClampedArray(data);
    boxBlur(data, 8, 8, 2, 1);
    expect([...data]).toEqual([...copy]);
  });

  it('spreads a single bright pixel and keeps the picture\'s total light (edges clamp, so it only shifts at the border)', () => {
    const w = 21;
    const data = solid(w, w, [0, 0, 0, 255]);
    const centre = (10 * w + 10) * 4;
    data.set([255, 255, 255, 255], centre);
    const out = boxBlur(data, w, w, 1, 2);
    const at = (x: number, y: number) => out[(y * w + x) * 4]!;
    // Spread evenly over the 5x5 box around it, and nothing beyond.
    expect(at(10, 10)).toBe(Math.round((255 / 25) * 1));
    expect(at(8, 8)).toBe(at(12, 12));
    expect(at(13, 10)).toBe(0);
    let total = 0;
    for (let i = 0; i < w * w; i++) total += out[i * 4]!;
    expect(Math.abs(total - 255)).toBeLessThan(25);
  });

  it('three passes are softer than one: the peak falls', () => {
    const w = 31;
    const data = solid(w, w, [0, 0, 0, 255]);
    data.set([255, 255, 255, 255], (15 * w + 15) * 4);
    const peak = (passes: number) => boxBlur(data, w, w, passes, 3)[(15 * w + 15) * 4]!;
    expect(peak(3)).toBeLessThan(peak(1));
  });

  it('keeps each colour channel apart and the picture opaque', () => {
    const data = solid(10, 10, [255, 0, 0, 255]);
    const out = boxBlur(data, 10, 10, 3, 3);
    expect(out[0]).toBe(255);
    expect(out[1]).toBe(0);
    expect(out[2]).toBe(0);
    expect(out[3]).toBe(255);
  });

  it('boxBlurPass writes into the given array and is size-preserving', () => {
    const src = solid(5, 4, [10, 20, 30, 255]);
    const dst = new Uint8ClampedArray(src.length);
    boxBlurPass(src, dst, 5, 4, 1);
    expect(dst.length).toBe(src.length);
    expect([...dst]).toEqual([...src]);
  });
});
