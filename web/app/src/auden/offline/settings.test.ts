// @vitest-environment happy-dom
import { beforeEach, describe, expect, it } from 'vitest';
import { useOfflineSettings } from './settings';

beforeEach(() => localStorage.clear());

describe('offline settings', () => {
  it('Keep playlists updated is on by default and kept when turned off', async () => {
    expect(useOfflineSettings.getState().keepUpdated).toBe(true);
    useOfflineSettings.getState().setKeepUpdated(false);
    const raw = localStorage.getItem('auden:offline')!;
    expect(JSON.parse(raw).state.keepUpdated).toBe(false);
    useOfflineSettings.setState({ keepUpdated: true });
    localStorage.setItem('auden:offline', raw);
    await useOfflineSettings.persist.rehydrate();
    expect(useOfflineSettings.getState().keepUpdated).toBe(false);
  });
  it('a value saved before the switch existed reads as on, and keeps its other choices', async () => {
    localStorage.setItem('auden:offline', JSON.stringify({ state: { enabled: true, withLyrics: false }, version: 1 }));
    await useOfflineSettings.persist.rehydrate();
    expect(useOfflineSettings.getState()).toMatchObject({ enabled: true, withLyrics: false, keepUpdated: true });
  });
});
