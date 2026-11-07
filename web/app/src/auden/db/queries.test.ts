import { afterEach, describe, expect, it, vi } from 'vitest';
import { queryClient } from '@/lib/queryClient';
import { notifyStores } from './notify';
import { startAudenQuerySync } from './queries';

afterEach(() => {
  vi.useRealTimers();
  vi.restoreAllMocks();
});

const invalidated = (spy: ReturnType<typeof vi.spyOn>) => spy.mock.calls.map((c: unknown[]) => (c[0] as { queryKey: unknown[] }).queryKey[1]);

describe('startAudenQuerySync', () => {
  it('refetches playlist entries and playlists when tracks change, throttled with the track store', () => {
    vi.useFakeTimers();
    const spy = vi.spyOn(queryClient, 'invalidateQueries').mockResolvedValue();
    const stop = startAudenQuerySync();
    notifyStores('track');
    notifyStores('track');
    expect(spy).not.toHaveBeenCalled();
    vi.advanceTimersByTime(300);
    expect(invalidated(spy).sort()).toEqual(['playlist', 'playlist_entry', 'track']);
    spy.mockClear();
    notifyStores('playlist_entry');
    expect(invalidated(spy).sort()).toEqual(['playlist', 'playlist_entry']);
    stop();
  });
});
