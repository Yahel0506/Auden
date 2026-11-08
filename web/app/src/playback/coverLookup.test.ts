// @vitest-environment happy-dom
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { forgetCovers, resolvedCoverOf } from '@/lib/resolvedCovers';

vi.mock('@/data/music/api', () => ({ fetchTrackLinks: vi.fn() }));
const { requestCover, setCoverLookup } = await import('./coverLookup');

const flush = () => vi.advanceTimersByTimeAsync(0);
const ids = (n: number) => Array.from({ length: n }, (_, i) => `id${String(i).padStart(9, '0')}`);

beforeEach(() => {
  vi.useFakeTimers();
  forgetCovers();
});
afterEach(() => {
  setCoverLookup(null);
  vi.useRealTimers();
});

describe('requestCover', () => {
  it('finds a cover and keeps it for every row that asks again', async () => {
    const lookup = vi.fn(async (id: string) => ({ thumbnail: `https://yt3.googleusercontent.com/${id}` }));
    setCoverLookup(lookup);
    requestCover('aaaaaaaaaaa');
    await flush();
    expect(resolvedCoverOf('aaaaaaaaaaa')).toBe('https://yt3.googleusercontent.com/aaaaaaaaaaa');
    requestCover('aaaaaaaaaaa');
    await flush();
    expect(lookup).toHaveBeenCalledTimes(1);
  });

  it('asks two at a time, and the newest row waiting goes first', async () => {
    const releases: (() => void)[] = [];
    const order: string[] = [];
    setCoverLookup((id) => {
      order.push(id);
      return new Promise((r) => releases.push(() => r({ thumbnail: `https://x/${id}` })));
    });
    const [a, b, c, d] = ids(4) as [string, string, string, string];
    requestCover(a);
    requestCover(b);
    requestCover(c);
    requestCover(d);
    expect(order).toEqual([a, b]);
    releases[0]!();
    await flush();
    expect(order).toEqual([a, b, d]);
  });

  it('drops a row that went away before its turn', async () => {
    const releases: (() => void)[] = [];
    const order: string[] = [];
    setCoverLookup((id) => {
      order.push(id);
      return new Promise((r) => releases.push(() => r({ thumbnail: `https://x/${id}` })));
    });
    const [a, b, c] = ids(3) as [string, string, string];
    requestCover(a);
    requestCover(b);
    const cancelC = requestCover(c);
    cancelC();
    releases[0]!();
    await flush();
    expect(order).toEqual([a, b]);
  });

  it('does not ask for a local file, a clip, or a song YouTube Music has no cover for within a minute', async () => {
    const lookup = vi.fn(async () => null);
    setCoverLookup(lookup);
    requestCover('auden:local:abc');
    requestCover('auden:clip:1');
    requestCover('aaaaaaaaaaa');
    await flush();
    requestCover('aaaaaaaaaaa');
    await flush();
    expect(lookup).toHaveBeenCalledTimes(1);
    vi.advanceTimersByTime(61_000);
    requestCover('aaaaaaaaaaa');
    await flush();
    expect(lookup).toHaveBeenCalledTimes(2);
  });

  it('keeps only the newest few dozen asks', async () => {
    const asked: string[] = [];
    setCoverLookup((id) => {
      asked.push(id);
      return new Promise(() => {});
    });
    for (const id of ids(60)) requestCover(id);
    // Two went out at once; of the rest, only the newest 40 wait.
    expect(asked).toHaveLength(2);
  });
});
