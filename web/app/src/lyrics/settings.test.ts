// @vitest-environment happy-dom
import { beforeEach, describe, expect, it } from 'vitest';
import { clampOffset, OFFSET_MAX_MS, OFFSET_MIN_MS, useLyricsSettings } from './settings';

beforeEach(() => {
  localStorage.clear();
  useLyricsSettings.getState().reset();
});

describe('lyrics offset and extras settings', () => {
  it('keeps the global offset within five seconds either way, in whole milliseconds', () => {
    expect(clampOffset(9000)).toBe(OFFSET_MAX_MS);
    expect(clampOffset(-9000)).toBe(OFFSET_MIN_MS);
    expect(clampOffset(123.6)).toBe(124);
    useLyricsSettings.getState().setLyricsOffsetMs(7000);
    expect(useLyricsSettings.getState().lyricsOffsetMs).toBe(5000);
  });

  it('remembers the offset and the extra providers across a reload', () => {
    const s = useLyricsSettings.getState();
    s.setLyricsOffsetMs(-300);
    s.setExtra('kugou', true);
    s.setExtra('lrclib', true);
    s.setExtra('kugou', false);
    const saved = JSON.parse(localStorage.getItem('bitchord:lyrics')!);
    expect(saved.lyricsOffsetMs).toBe(-300);
    expect(saved.extras).toEqual(['lrclib']);
  });

  it('starts with no offset and no extras', () => {
    const s = useLyricsSettings.getState();
    expect(s.lyricsOffsetMs).toBe(0);
    expect(s.extras).toEqual([]);
  });
});
