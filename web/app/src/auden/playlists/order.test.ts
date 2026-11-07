import { describe, expect, it } from 'vitest';
import { dropBefore } from './order';

const order = [1, 2, 3, 4].map((entryId) => ({ entryId }));

describe('dropBefore', () => {
  it('dropping up inserts before the target', () => {
    expect(dropBefore(order, 4, 2)).toBe(2);
  });
  it('dropping down inserts after the target, and onto the last row puts the song last', () => {
    expect(dropBefore(order, 1, 3)).toBe(4);
    expect(dropBefore(order, 1, 4)).toBeNull();
  });
  it('an unknown id falls back to the target', () => {
    expect(dropBefore(order, 99, 2)).toBe(2);
  });
});
