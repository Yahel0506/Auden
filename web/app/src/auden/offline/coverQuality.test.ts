import { describe, expect, it, vi } from 'vitest';
import { upgradeLowCovers } from './coverQuality';
import type { OfflineItem } from './types';

const blob = (name: string) => new Blob([name]);
const item = (id: string, cover: Blob | null, thumbnailUrl: string | null = `https://lh3.googleusercontent.com/${id}=w60-h60`): OfflineItem =>
  ({ videoId: id, thumbnailUrl, cover, file: `${id}.webm`, mime: 'audio/webm', bytes: 1, itag: 251, savedAt: 0 }) as unknown as OfflineItem;

function setup(items: OfflineItem[], widths: Record<string, number>, better: Record<string, Blob | null> = {}) {
  const put = vi.fn(async () => {});
  const fetchCover = vi.fn(async (url: string) => better[url.split('/').pop()!.split('=')[0]!] ?? null);
  const onUpgraded = vi.fn();
  const run = () =>
    upgradeLowCovers({ items: async () => items, putItem: put, fetchCover, width: async (b) => widths[await b.text()] ?? 0, onUpgraded });
  return { put, fetchCover, onUpgraded, run };
}

describe('upgradeLowCovers', () => {
  it('fetches a sharp cover again for a copy whose saved one is small, and saves it over', async () => {
    const small = blob('small-a');
    const sharp = blob('sharp-a');
    const t = setup([item('a', small)], { 'small-a': 120, 'sharp-a': 544 }, { a: sharp });
    expect(await t.run()).toBe(1);
    expect(t.put).toHaveBeenCalledWith(expect.objectContaining({ videoId: 'a', cover: sharp }));
    expect(t.onUpgraded).toHaveBeenCalledTimes(1);
  });

  it('leaves a sharp cover, a copy with no cover or no URL, and a cover that cannot be improved', async () => {
    const t = setup(
      [item('a', blob('sharp-a')), item('b', null), item('c', blob('small-c'), null), item('d', blob('small-d')), item('e', blob('small-e'))],
      { 'sharp-a': 544, 'small-c': 60, 'small-d': 120, 'small-e': 120, 'same-e': 120 },
      { d: null, e: blob('same-e') },
    );
    expect(await t.run()).toBe(0);
    expect(t.put).not.toHaveBeenCalled();
    expect(t.onUpgraded).not.toHaveBeenCalled();
    expect(t.fetchCover).toHaveBeenCalledTimes(2);
  });
});
