// @vitest-environment happy-dom
import { describe, expect, it } from 'vitest';
import { clipsAllowedOn, useClipNetwork } from './canvas';

describe('clipsAllowedOn (Animated cover and Background video share it)', () => {
  it('is off over cellular by default, on once allowed', () => {
    expect(clipsAllowedOn(false, { type: 'cellular' })).toBe(false);
    expect(clipsAllowedOn(true, { type: 'cellular' })).toBe(true);
  });
  it('never overrides Save-Data', () => {
    expect(clipsAllowedOn(true, { saveData: true, type: 'cellular' })).toBe(false);
    expect(clipsAllowedOn(true, { saveData: true })).toBe(false);
  });
  it('lets Wi-Fi and an unreporting browser through', () => {
    expect(clipsAllowedOn(false, { type: 'wifi' })).toBe(true);
    expect(clipsAllowedOn(false, undefined)).toBe(true);
  });
  it('defaults off and is kept', () => {
    expect(useClipNetwork.getState().overCellular).toBe(false);
    useClipNetwork.getState().setOverCellular(true);
    expect(JSON.parse(localStorage.getItem('bitchord:clips')!).state).toEqual({ overCellular: true });
    useClipNetwork.getState().setOverCellular(false);
  });
});
