// @vitest-environment happy-dom
import { act, createElement } from 'react';
import { createRoot, type Root } from 'react-dom/client';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { RetryImg } from './RetryImg';

(globalThis as { IS_REACT_ACT_ENVIRONMENT?: boolean }).IS_REACT_ACT_ENVIRONMENT = true;

let root: Root;
let host: HTMLElement;
beforeEach(() => {
  vi.useFakeTimers();
  host = document.createElement('div');
  document.body.appendChild(host);
  root = createRoot(host);
});
afterEach(async () => {
  await act(async () => root.unmount());
  host.remove();
  vi.useRealTimers();
});

const img = () => host.querySelector('img')!;
const fail = () => act(async () => void img().dispatchEvent(new Event('error')));
const wait = (ms: number) => act(async () => void vi.advanceTimersByTime(ms));
const mount = (props: Record<string, unknown> = {}) =>
  act(async () => root.render(createElement(RetryImg, { src: 'https://lh3.googleusercontent.com/x=w120-h120', fallbackSrc: 'https://lh3.googleusercontent.com/x', ...props })));

describe('RetryImg', () => {
  it('asks again after a pause when the CDN refuses, then with the artwork’s own URL, then lets go', async () => {
    const onError = vi.fn();
    await mount({ onError });
    const first = img();
    await fail();
    // Nothing is retried at once: a burst is what got it refused.
    expect(img()).toBe(first);
    await wait(1500);
    expect(img()).not.toBe(first);
    expect(img().getAttribute('src')).toBe('https://lh3.googleusercontent.com/x=w120-h120');
    await fail();
    await wait(3500);
    expect(img().getAttribute('src')).toBe('https://lh3.googleusercontent.com/x');
    expect(onError).not.toHaveBeenCalled();
    await fail();
    expect(onError).toHaveBeenCalledTimes(1);
  });

  it('starts over for a new cover', async () => {
    await mount();
    await fail();
    await wait(1500);
    await mount({ src: 'https://lh3.googleusercontent.com/y=w120-h120', fallbackSrc: 'https://lh3.googleusercontent.com/y' });
    expect(img().getAttribute('src')).toBe('https://lh3.googleusercontent.com/y=w120-h120');
    await fail();
    await wait(1500);
    expect(img().getAttribute('src')).toBe('https://lh3.googleusercontent.com/y=w120-h120');
  });
});
