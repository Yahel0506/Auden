import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { offlineCoverUrl, replaceKept } from './kept';

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

describe('offlineCoverUrl', () => {
  it('is the cover saved with the copy, as one URL that is made once', () => {
    const blob = new Blob(['jpg'], { type: 'image/jpeg' });
    replaceKept([['a', copy(blob)]]);
    expect(offlineCoverUrl('a')).toBe('blob:cover-1');
    expect(offlineCoverUrl('a')).toBe('blob:cover-1');
  });

  it('is null for a song with no copy or no saved cover', () => {
    replaceKept([['a', copy(null)]]);
    expect(offlineCoverUrl('a')).toBeNull();
    expect(offlineCoverUrl('nope')).toBeNull();
  });

  it('lets the URL go when the copy goes or its picture changes, and keeps it while neither does', () => {
    const one = new Blob(['1']);
    const two = new Blob(['2']);
    replaceKept([['a', copy(one)], ['b', copy(one)]]);
    offlineCoverUrl('a');
    offlineCoverUrl('b');
    replaceKept([['a', copy(one)], ['b', copy(two)]]);
    expect(revoked).toEqual(['blob:cover-2']);
    replaceKept([['a', copy(one)]]);
    expect(offlineCoverUrl('a')).toBe('blob:cover-1');
  });
});
