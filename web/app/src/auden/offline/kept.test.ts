import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { offlineCoverUrl, replaceKept, setCoverPx } from './kept';

let n = 0;
const revoked: string[] = [];
beforeEach(() => {
  n = 0;
  revoked.length = 0;
  vi.stubGlobal('URL', Object.assign(function URLStub() {}, { createObjectURL: () => `blob:cover-${++n}`, revokeObjectURL: (u: string) => void revoked.push(u) }));
});
afterEach(() => {
  replaceKept([]);
  vi.unstubAllGlobals();
});

const copy = (cover: Blob | null) => ({ file: 'f.webm', mime: 'audio/webm', cover });
// Most tests are about the URL's life: their covers are sharp.
const sharpCopy = (id: string, cover: Blob) => {
  setCoverPx(id, cover, 544);
  return copy(cover);
};

describe('offlineCoverUrl', () => {
  it('is the cover saved with the copy, as one URL that is made once', () => {
    const blob = new Blob(['jpg'], { type: 'image/jpeg' });
    replaceKept([['a', sharpCopy('a', blob)]]);
    expect(offlineCoverUrl('a')).toBe('blob:cover-1');
    expect(offlineCoverUrl('a')).toBe('blob:cover-1');
  });

  it('is null for a song with no copy or no saved cover', () => {
    replaceKept([['a', copy(null)]]);
    expect(offlineCoverUrl('a')).toBeNull();
    expect(offlineCoverUrl('nope')).toBeNull();
  });

  it('keeps the same URL when the store hands back a new Blob for the same copy', () => {
    replaceKept([['a', sharpCopy('a', new Blob(['1']))]]);
    expect(offlineCoverUrl('a')).toBe('blob:cover-1');
    // Every read of the store makes a new Blob object: the cover on screen must not go blank.
    replaceKept([['a', sharpCopy('a', new Blob(['1']))]]);
    expect(revoked).toEqual([]);
    expect(offlineCoverUrl('a')).toBe('blob:cover-1');
  });

  it('lets the URL go when the copy is removed or no longer has a cover', () => {
    replaceKept([['a', sharpCopy('a', new Blob(['1']))], ['b', sharpCopy('b', new Blob(['2']))]]);
    offlineCoverUrl('a');
    offlineCoverUrl('b');
    replaceKept([['a', copy(null)]]);
    expect(revoked.sort()).toEqual(['blob:cover-1', 'blob:cover-2']);
    expect(offlineCoverUrl('a')).toBeNull();
  });

  it('shows a blurry saved cover only when there is no network, and a sharp one always', () => {
    const blur = new Blob(['blur']);
    const sharp = new Blob(['sharper than that']);
    replaceKept([['a', copy(blur)], ['b', copy(sharp)]]);
    setCoverPx('a', blur, 120);
    setCoverPx('b', sharp, 544);
    vi.stubGlobal('navigator', { onLine: true });
    expect(offlineCoverUrl('a')).toBeNull();
    expect(offlineCoverUrl('b')).not.toBeNull();
    vi.stubGlobal('navigator', { onLine: false });
    expect(offlineCoverUrl('a')).not.toBeNull();
  });
});
