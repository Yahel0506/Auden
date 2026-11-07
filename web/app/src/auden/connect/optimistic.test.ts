import { describe, expect, it } from 'vitest';
import type { PlaybackState } from '@bitchord/shared/connect';
import { applyOptimistic, reorderUpcoming, type Held } from './optimistic';

const t = (id: string, durationSec = 200) => ({ videoId: id, title: id, artist: 'A', durationSec });
const held = (over: Partial<PlaybackState> = {}): Held => ({
  state: { queue: ['a', 'b', 'c', 'd'].map((i) => t(i)), index: 1, positionMs: 10_000, isPlaying: true, volume: 0.5, shuffle: false, repeat: 'off', ...over },
  atServerMs: 1000,
});
const ids = (h: Held) => h.state.queue.map((x) => x.videoId);

describe('what a mirror shows before the playing device answers', () => {
  it('play, pause and toggle keep the position the song had reached', () => {
    const paused = applyOptimistic(held(), { action: 'toggle' }, 6000);
    expect(paused.state).toMatchObject({ isPlaying: false, positionMs: 15_000 });
    expect(paused.atServerMs).toBe(6000);
    expect(applyOptimistic(paused, { action: 'toggle' }, 9000).state).toMatchObject({ isPlaying: true, positionMs: 15_000 });
    expect(applyOptimistic(held({ isPlaying: false }), { action: 'play' }, 5000).state.isPlaying).toBe(true);
    expect(applyOptimistic(held(), { action: 'pause' }, 5000).state.isPlaying).toBe(false);
  });

  it('seek, volume, repeat', () => {
    expect(applyOptimistic(held(), { action: 'seek', value: 90_000 }, 2000).state.positionMs).toBe(90_000);
    expect(applyOptimistic(held(), { action: 'volume', value: 3 }, 2000).state.volume).toBe(1);
    expect(applyOptimistic(held(), { action: 'repeat', value: 'one' }, 2000).state.repeat).toBe('one');
  });

  it('next, previous and jump move the song and start it over', () => {
    expect(applyOptimistic(held(), { action: 'next' }, 2000).state).toMatchObject({ index: 2, positionMs: 0 });
    expect(applyOptimistic(held({ index: 3 }), { action: 'next' }, 2000).state.index).toBe(3);
    expect(applyOptimistic(held({ index: 3, repeat: 'all' }), { action: 'next' }, 2000).state.index).toBe(0);
    // Far into the song, previous restarts it; near its start it goes back.
    expect(applyOptimistic(held(), { action: 'previous' }, 2000).state).toMatchObject({ index: 1, positionMs: 0 });
    expect(applyOptimistic(held({ positionMs: 500 }), { action: 'previous' }, 1100).state).toMatchObject({ index: 0, positionMs: 0 });
    expect(applyOptimistic(held({ isPlaying: false }), { action: 'jump', value: 3 }, 2000).state).toMatchObject({ index: 3, positionMs: 0, isPlaying: true });
    expect(applyOptimistic(held(), { action: 'jump', value: 9 }, 2000)).toEqual(held());
  });

  it('shuffle off lands on the order the state carries; on only flips the flag', () => {
    const shuffled = held({ queue: ['a', 'd', 'c', 'b'].map((i) => t(i)), index: 0, shuffle: true, order: ['a', 'b', 'c', 'd'] });
    const off = applyOptimistic(shuffled, { action: 'shuffle', value: false }, 2000);
    expect(ids(off)).toEqual(['a', 'b', 'c', 'd']);
    expect(off.state.shuffle).toBe(false);
    expect(off.state.order).toBeUndefined();
    const on = applyOptimistic(held(), { action: 'shuffle', value: true }, 2000);
    expect(on.state.shuffle).toBe(true);
    expect(ids(on)).toEqual(['a', 'b', 'c', 'd']);
  });

  it('queue edits', () => {
    expect(ids(applyOptimistic(held(), { action: 'playNext', tracks: [t('n')] }, 2000))).toEqual(['a', 'b', 'n', 'c', 'd']);
    expect(ids(applyOptimistic(held(), { action: 'addToQueue', tracks: [t('z')] }, 2000))).toEqual(['a', 'b', 'c', 'd', 'z']);
    const removed = applyOptimistic(held(), { action: 'remove', value: 0 }, 2000);
    expect(ids(removed)).toEqual(['b', 'c', 'd']);
    expect(removed.state.index).toBe(0);
    expect(applyOptimistic(held(), { action: 'remove', value: 1 }, 2000)).toEqual(held());
    expect(ids(applyOptimistic(held(), { action: 'move', value: 3, to: 2 }, 2000))).toEqual(['a', 'b', 'd', 'c']);
    expect(applyOptimistic(held(), { action: 'move', value: 0, to: 2 }, 2000)).toEqual(held());
    expect(ids(applyOptimistic(held(), { action: 'clearUpcoming' }, 2000))).toEqual(['a', 'b']);
  });

  it('keeps the order list in step with the queue edits', () => {
    const s = held({ shuffle: true, order: ['d', 'c', 'b', 'a'] });
    expect(applyOptimistic(s, { action: 'remove', value: 3 }, 2000).state.order).toEqual(['c', 'b', 'a']);
    expect(applyOptimistic(s, { action: 'addToQueue', tracks: [t('z')] }, 2000).state.order).toEqual(['d', 'c', 'b', 'a', 'z']);
    expect(applyOptimistic(s, { action: 'clearUpcoming' }, 2000).state.order).toEqual(['b', 'a']);
  });

  it('reorderUpcoming leaves what played alone and puts unknown songs last', () => {
    expect(reorderUpcoming(['a', 'c', 'x', 'b'].map((i) => t(i)), 0, ['a', 'b', 'c']).map((x) => x.videoId)).toEqual(['a', 'b', 'c', 'x']);
  });
});
