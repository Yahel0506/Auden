import { describe, expect, it } from 'vitest';
import { cursorMs, effectiveOffset, formatOffset, seekMs } from './offset';

describe('lyrics offset', () => {
  it('a song\'s own offset wins over the global one, and null follows it', () => {
    expect(effectiveOffset(300, -200)).toBe(300);
    expect(effectiveOffset(0, -200)).toBe(0);
    expect(effectiveOffset(null, -200)).toBe(-200);
    expect(effectiveOffset(undefined, 400)).toBe(400);
  });

  it('a positive offset shows the lyrics later: the cursor is the playhead minus it', () => {
    expect(cursorMs(10_000, 500)).toBe(9_500);
    expect(cursorMs(10_000, -500)).toBe(10_500);
    expect(cursorMs(200, 500)).toBe(0);
  });

  it('a tap on a line seeks to its time plus the offset, so the line is the one on screen', () => {
    expect(seekMs(9_500, 500)).toBe(10_000);
    // The two are inverses: tapping a line puts the cursor exactly on it.
    expect(cursorMs(seekMs(9_500, 500), 500)).toBe(9_500);
    expect(seekMs(100, -500)).toBe(0);
  });

  it('formats like the phone\'s sheet', () => {
    expect(formatOffset(300)).toBe('+0.3 s');
    expect(formatOffset(-1200)).toBe('-1.2 s');
    expect(formatOffset(0)).toBe('0.0 s');
  });
});
