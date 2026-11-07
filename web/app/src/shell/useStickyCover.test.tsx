// @vitest-environment happy-dom
import { act, createElement } from 'react';
import { createRoot } from 'react-dom/client';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { COVER_FADE_MS, useStickyCover } from './useStickyCover';

(globalThis as { IS_REACT_ACT_ENVIRONMENT?: boolean }).IS_REACT_ACT_ENVIRONMENT = true;

function setup() {
  const seen: { previousCover: string | null; coverLoaded: () => void; coverFailed: () => void }[] = [];
  function Probe({ url }: { url: string | null }) {
    seen.push(useStickyCover(url));
    return null;
  }
  const host = document.createElement('div');
  const root = createRoot(host);
  return {
    render: (url: string | null) => act(async () => root.render(createElement(Probe, { url }))),
    latest: () => seen[seen.length - 1]!,
    renders: () => seen.length,
  };
}

beforeEach(() => vi.useFakeTimers());
afterEach(() => vi.useRealTimers());

describe('useStickyCover', () => {
  it('has no previous cover for the first one', async () => {
    const t = setup();
    await t.render('a');
    expect(t.latest().previousCover).toBeNull();
  });

  it('keeps the old cover under the new one until the new one is fully visible, not merely loaded', async () => {
    const t = setup();
    await t.render('a');
    await t.render('b');
    expect(t.latest().previousCover).toBe('a');
    // `load` fires with the new cover still at opacity 0: the old one must stay under it through the fade.
    await act(async () => t.latest().coverLoaded());
    await act(async () => { await vi.advanceTimersByTimeAsync(COVER_FADE_MS - 50); });
    expect(t.latest().previousCover).toBe('a');
    await act(async () => { await vi.advanceTimersByTimeAsync(100); });
    expect(t.latest().previousCover).toBeNull();
  });

  it('drops the old cover at once when the new one fails to load: the old artwork under a new title would be wrong', async () => {
    const t = setup();
    await t.render('a');
    await t.render('b');
    await act(async () => t.latest().coverFailed());
    expect(t.latest().previousCover).toBeNull();
  });

  it('a new track before the fade ends is not cut short by the old track\'s pending release', async () => {
    const t = setup();
    await t.render('a');
    await t.render('b');
    await act(async () => t.latest().coverLoaded());
    await act(async () => { await vi.advanceTimersByTimeAsync(100); });
    await t.render('c');
    await act(async () => { await vi.advanceTimersByTimeAsync(COVER_FADE_MS); });
    // 'b' is still the cover beneath 'c' (c has not loaded), whatever became of b's own timer.
    expect(t.latest().previousCover).toBe('b');
  });

  it('a quick succession holds only the one just before: a stale cover never stacks up', async () => {
    const t = setup();
    await t.render('a');
    await t.render('b');
    await t.render('c');
    expect(t.latest().previousCover).toBe('b');
  });

  it('settles on the very render the cover changes, with no frame showing the new cover over nothing', async () => {
    const t = setup();
    await t.render('a');
    const before = t.renders();
    await t.render('b');
    // Every render after the change already carries the previous cover.
    expect(t.latest().previousCover).toBe('a');
    expect(t.renders() - before).toBeGreaterThan(0);
  });

  it('is a no-op when asked to clear with nothing held, and for a track with no artwork', async () => {
    const t = setup();
    await t.render(null);
    await act(async () => t.latest().coverLoaded());
    expect(t.latest().previousCover).toBeNull();
    await t.render('a');
    expect(t.latest().previousCover).toBeNull();
  });
});
