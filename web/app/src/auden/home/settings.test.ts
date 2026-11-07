// @vitest-environment happy-dom
import { beforeEach, describe, expect, it } from 'vitest';
import { useHomeSettings } from './settings';

beforeEach(() => localStorage.clear());

describe('Home settings persistence', () => {
  it('keeps the new switches across a reload', async () => {
    const s = useHomeSettings.getState();
    s.setHideRecents(true);
    s.setHideVideos(true);
    s.setSquareVideoThumbnails(true);
    const stored = JSON.parse(localStorage.getItem('auden:home')!).state;
    expect(stored).toMatchObject({ hideRecents: true, hideVideos: true, squareVideoThumbnails: true });
    const raw = localStorage.getItem('auden:home')!;
    useHomeSettings.setState({ hideRecents: false, hideVideos: false, squareVideoThumbnails: false });
    localStorage.setItem('auden:home', raw);
    await useHomeSettings.persist.rehydrate();
    expect(useHomeSettings.getState()).toMatchObject({ hideRecents: true, hideVideos: true, squareVideoThumbnails: true });
  });

  it('reads a value saved by an older build (without them) as off, and keeps its shelves', async () => {
    localStorage.setItem('auden:home', JSON.stringify({ state: { localWeight: 40, enabled: { LOOP: false } }, version: 1 }));
    await useHomeSettings.persist.rehydrate();
    const s = useHomeSettings.getState();
    expect(s).toMatchObject({ localWeight: 40, hideRecents: false, hideVideos: false, squareVideoThumbnails: false });
    expect(s.enabled.LOOP).toBe(false);
    expect(s.enabled.REDISCOVER).toBe(true);
  });
});
