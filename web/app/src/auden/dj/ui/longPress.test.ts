import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { createLongPress } from './longPress';

beforeEach(() => vi.useFakeTimers());
afterEach(() => vi.useRealTimers());

describe('createLongPress', () => {
  it('fires once after the hold, and its click is consumed (not also a tap)', () => {
    const onLong = vi.fn();
    const p = createLongPress(onLong, 500);
    p.down();
    vi.advanceTimersByTime(499);
    expect(onLong).not.toHaveBeenCalled();
    vi.advanceTimersByTime(1);
    expect(onLong).toHaveBeenCalledTimes(1);
    expect(p.consumeLong()).toBe(true);
    expect(p.consumeLong()).toBe(false);
  });
  it('a short press, a pointer that leaves, or a cancelled gesture never fires', () => {
    const onLong = vi.fn();
    const p = createLongPress(onLong, 500);
    p.down();
    vi.advanceTimersByTime(200);
    p.cancel(); // pointerup / leave / cancel
    vi.advanceTimersByTime(1000);
    expect(onLong).not.toHaveBeenCalled();
    expect(p.consumeLong()).toBe(false); // a tap
  });
  it('a new press restarts the clock and clears the old held state', () => {
    const onLong = vi.fn();
    const p = createLongPress(onLong, 500);
    p.down();
    vi.advanceTimersByTime(600);
    p.down();
    expect(p.consumeLong()).toBe(false);
    vi.advanceTimersByTime(499);
    expect(onLong).toHaveBeenCalledTimes(1);
  });
});
