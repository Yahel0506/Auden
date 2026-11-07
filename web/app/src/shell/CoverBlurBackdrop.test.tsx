// @vitest-environment happy-dom
import { act, createElement } from 'react';
import { createRoot, type Root } from 'react-dom/client';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';

(globalThis as { IS_REACT_ACT_ENVIRONMENT?: boolean }).IS_REACT_ACT_ENVIRONMENT = true;

const blurred = vi.hoisted(() => vi.fn());
vi.mock('@/lib/coverBlur', () => ({ blurredCover: blurred }));

const { BLUR_FADE_MS, CoverBlurBackdrop, MAIN_SCRIM, MeshScrim, SCRIM_MS } = await import('./CoverBlurBackdrop');

let root: Root;
let host: HTMLElement;
beforeEach(() => {
  vi.useFakeTimers({ toFake: ['setTimeout', 'clearTimeout', 'requestAnimationFrame', 'cancelAnimationFrame'] });
  blurred.mockReset();
  host = document.createElement('div');
  document.body.appendChild(host);
  root = createRoot(host);
});
afterEach(async () => {
  await act(async () => root.unmount());
  host.remove();
  vi.useRealTimers();
});

const render = (artworkUrl: string | null, column: boolean, solid = false) => act(async () => root.render(createElement(CoverBlurBackdrop, { artworkUrl, column, solid })));
const imgs = () => [...host.querySelectorAll('img')];
const container = () => host.firstElementChild as HTMLElement;

describe('CoverBlurBackdrop', () => {
  it('shows nothing until a picture is ready, and the first one at once', async () => {
    blurred.mockResolvedValue('blob:one');
    await render('a', false);
    await act(async () => { await vi.advanceTimersByTimeAsync(0); });
    expect(imgs().map((i) => i.getAttribute('src'))).toEqual(['blob:one']);
    expect(imgs()[0]!.style.opacity).toBe('1');
  });

  it('is hidden in the main view and lifts in with the lyrics or the queue', async () => {
    blurred.mockResolvedValue('blob:one');
    await render('a', false);
    await act(async () => { await vi.advanceTimersByTimeAsync(0); });
    expect(container().style.opacity).toBe('0');
    expect(container().style.transitionDuration).toBe(`${SCRIM_MS}ms`);
    await render('a', true);
    expect(container().style.opacity).toBe('1');
  });

  it('with no picture to show (a cover that cannot be read) it stays out of the way and leaves the mesh', async () => {
    blurred.mockRejectedValue(new Error('tainted'));
    await render('a', true);
    await act(async () => { await vi.advanceTimersByTimeAsync(0); });
    expect(imgs()).toHaveLength(0);
    expect(container().style.opacity).toBe('0');
  });

  it('as the Blur theme’s floor it is opaque with no picture at all: no art, an unreadable cover, or the blur not made yet', async () => {
    blurred.mockReturnValue(new Promise(() => {}));
    await render('a', true, true);
    expect(imgs()).toHaveLength(0);
    expect(container().style.opacity).toBe('1');
    await render(null, true, true);
    expect(container().style.opacity).toBe('1');
    blurred.mockRejectedValue(new Error('tainted'));
    await render('b', true, true);
    await act(async () => { await vi.advanceTimersByTimeAsync(0); });
    expect(container().style.opacity).toBe('1');
  });

  it('keeps the picture showing until the next is made, then crossfades over it and lets the old one go', async () => {
    blurred.mockResolvedValueOnce('blob:one');
    await render('a', true);
    await act(async () => { await vi.advanceTimersByTimeAsync(0); });
    let release!: (src: string) => void;
    blurred.mockImplementationOnce(() => new Promise<string>((r) => (release = r)));
    await render('b', true);
    // The new picture is not ready: the old one stays, nothing dips.
    expect(imgs().map((i) => i.getAttribute('src'))).toEqual(['blob:one']);
    await act(async () => { release('blob:two'); await vi.advanceTimersByTimeAsync(0); });
    expect(imgs().map((i) => i.getAttribute('src'))).toEqual(['blob:one', 'blob:two']);
    await act(async () => { await vi.advanceTimersByTimeAsync(20); });
    expect(imgs()[1]!.style.opacity).toBe('1');
    await act(async () => { await vi.advanceTimersByTimeAsync(BLUR_FADE_MS + 100); });
    expect(imgs().map((i) => i.getAttribute('src'))).toEqual(['blob:two']);
  });

  it('leaves the picture on screen when the next cover cannot be read', async () => {
    blurred.mockResolvedValueOnce('blob:one');
    await render('a', true);
    await act(async () => { await vi.advanceTimersByTimeAsync(0); });
    blurred.mockRejectedValueOnce(new Error('tainted'));
    await render('b', true);
    await act(async () => { await vi.advanceTimersByTimeAsync(500); });
    expect(imgs().map((i) => i.getAttribute('src'))).toEqual(['blob:one']);
  });

  it('asks for nothing with no artwork', async () => {
    await render(null, true);
    expect(blurred).not.toHaveBeenCalled();
  });
});

describe('MeshScrim', () => {
  it('is lighter in the main view (0.6 of the beside-lyrics scrim) and gone when the blur and its own scrim take over', async () => {
    await act(async () => root.render(createElement(MeshScrim, { column: false })));
    expect((host.firstElementChild as HTMLElement).style.opacity).toBe(String(MAIN_SCRIM));
    await act(async () => root.render(createElement(MeshScrim, { column: true })));
    expect((host.firstElementChild as HTMLElement).style.opacity).toBe('0');
  });
});
