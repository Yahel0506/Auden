import { afterEach, describe, expect, it } from 'vitest';
import { clipBlob, clipGcPlan, collectClips, heldClipCount, registerClip, releaseClip } from './clips';
import { clipTrackId } from './source';

const blob = () => new Blob(['voice'], { type: 'audio/mpeg' });

describe('clipGcPlan', () => {
  it('marks clips live for the first time and releases those that left', () => {
    const held = new Map([
      ['new', { seen: false }],
      ['queued', { seen: true }],
      ['gone', { seen: true }],
      ['never', { seen: false }],
    ]);
    expect(clipGcPlan(held, new Set(['new', 'queued']))).toEqual({ seen: ['new'], release: ['gone'] });
  });
});

describe('collectClips', () => {
  afterEach(() => {
    for (const id of ['a', 'b', 'c']) releaseClip(id);
  });

  it('keeps a clip registered before it is enqueued', () => {
    registerClip('a', blob());
    expect(collectClips(['someVideo'])).toEqual([]);
    expect(clipBlob('a')).not.toBeNull();
  });

  it('releases a clip once it has left the queue and is not playing', () => {
    registerClip('a', blob());
    registerClip('b', blob());
    collectClips([clipTrackId('a'), clipTrackId('b'), 'v1']);
    // 'a' is still playing (nowPlaying), 'b' is gone.
    expect(collectClips(['v1', clipTrackId('a')])).toEqual(['b']);
    expect(clipBlob('a')).not.toBeNull();
    expect(clipBlob('b')).toBeNull();
    expect(collectClips(['v1'])).toEqual(['a']);
    expect(heldClipCount()).toBe(0);
  });

  it('ignores non-clip ids', () => {
    registerClip('c', blob());
    collectClips([clipTrackId('c')]);
    expect(collectClips(['auden:local:c', 'c'])).toEqual(['c']);
  });
});
