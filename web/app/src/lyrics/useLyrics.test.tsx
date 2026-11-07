// @vitest-environment happy-dom
import 'fake-indexeddb/auto';
import { QueryClientProvider } from '@tanstack/react-query';
import { IDBFactory } from 'fake-indexeddb';
import { act, createElement } from 'react';
import { createRoot, type Root } from 'react-dom/client';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { create } from 'zustand';
import { startAudenQuerySync } from '@/auden/db/queries';
import { closeAudenDb } from '@/auden/db/schema';
import { removeVariant, saveVariant, setActiveProvider } from '@/auden/lyrics/store';
import { queryClient } from '@/lib/queryClient';
import { line } from './model';
import type { LyricsResult } from './repository';
import { writeTtml } from './ttml';

(globalThis as { IS_REACT_ACT_ENVIRONMENT?: boolean }).IS_REACT_ACT_ENVIRONMENT = true;

const player = create<{ nowPlaying: { videoId: string; title: string; artist: string; source: string; durationSec: number } | null; queue: unknown[]; index: number }>()(() => ({
  nowPlaying: { videoId: 'abcdefghijk', title: 'Song', artist: 'Artist', source: 'youtube', durationSec: 200 },
  queue: [],
  index: 0,
}));
vi.mock('@/state/player', () => ({ usePlayer: Object.assign((sel: (s: unknown) => unknown) => player(sel as never), { getState: () => player.getState() }) }));

const lookup = vi.fn();
vi.mock('./repository', async (original) => ({ ...(await original<typeof import('./repository')>()), lookupLyrics: (...args: unknown[]) => lookup(...args), forgetLyrics: () => {} }));
vi.mock('./http', () => ({ onGranted: () => {}, lyricsGet: async () => null, ungrantedOrigins: new Set(), onUngrantedChange: () => () => {} }));

const { useLyrics, useLyricsUi } = await import('./useLyrics');

const lyrics = (source: LyricsResult['source'], text: string): LyricsResult => {
  const lines = [line(1000, text)];
  return { source, label: source, lines, ttml: writeTtml(lines), wordSynced: false, synced: true };
};

let root: Root;
let latest: ReturnType<typeof useLyrics>;
let stopSync: () => void;
function Probe() {
  latest = useLyrics();
  return null;
}
const settle = () => act(async () => { await new Promise((r) => setTimeout(r, 60)); });

beforeEach(async () => {
  globalThis.indexedDB = new IDBFactory();
  lookup.mockReset();
  queryClient.clear();
  useLyricsUi.setState({ picked: {}, states: {} });
  stopSync = startAudenQuerySync();
  root = createRoot(document.createElement('div'));
  await act(async () => root.render(createElement(QueryClientProvider, { client: queryClient }, createElement(Probe))));
});
afterEach(async () => {
  await act(async () => root.unmount());
  stopSync();
  await closeAudenDb();
});

describe('useLyrics with saved lyrics', () => {
  it('asks the providers when nothing is saved', async () => {
    lookup.mockResolvedValue(lyrics('lrclib', 'from the network'));
    await settle();
    expect(lookup).toHaveBeenCalledTimes(1);
    expect(latest.data?.lines[0]!.text).toBe('from the network');
    expect(latest.saved).toBe(false);
  });

  it('serves the song\'s chosen saved variant without asking anyone', async () => {
    await saveVariant('abcdefghijk', lyrics('kugou', 'saved offline'), { makeActive: true });
    lookup.mockResolvedValue(lyrics('lrclib', 'from the network'));
    await settle();
    expect(lookup).not.toHaveBeenCalled();
    expect(latest.data?.lines[0]!.text).toBe('saved offline');
    expect(latest).toMatchObject({ saved: true, chosen: 'kugou', songKey: 'abcdefghijk' });
  });

  it('shows a saved variant the moment it is saved, and goes back to the providers when it is deleted', async () => {
    lookup.mockResolvedValue(lyrics('lrclib', 'from the network'));
    await settle();
    expect(latest.saved).toBe(false);
    await act(async () => { await saveVariant('abcdefghijk', lyrics('kugou', 'kept'), { makeActive: true }); });
    await settle();
    expect(latest.data?.lines[0]!.text).toBe('kept');
    await act(async () => { await removeVariant('abcdefghijk', 'kugou'); });
    await settle();
    expect(latest.saved).toBe(false);
    expect(latest.data?.lines[0]!.text).toBe('from the network');
  });

  it('asks the remembered provider first, and the whole chain when it has nothing', async () => {
    await setActiveProvider('abcdefghijk', 'kugou');
    lookup.mockImplementation(async (_t: unknown, options: { only?: string }) => (options.only ? null : lyrics('lrclib', 'chain')));
    await settle();
    expect(lookup.mock.calls.map((c) => (c[1] as { only?: string }).only)).toEqual(['kugou', undefined]);
    expect(latest.data?.lines[0]!.text).toBe('chain');
    expect(latest.chosen).toBe('kugou');
  });

  it('keeps "Not on this provider" for one picked this session: no fallback', async () => {
    useLyricsUi.getState().pick('abcdefghijk', 'kugou');
    lookup.mockResolvedValue(null);
    await settle();
    expect(lookup).toHaveBeenCalledTimes(1);
    expect(latest.data).toBeNull();
  });

  it('does not look anything up for a DJ line', async () => {
    player.setState({ nowPlaying: { videoId: 'auden:clip:1', title: 'DJ', artist: 'Auden', source: 'clip', durationSec: 5 } });
    await settle();
    expect(lookup).not.toHaveBeenCalled();
    player.setState({ nowPlaying: { videoId: 'abcdefghijk', title: 'Song', artist: 'Artist', source: 'youtube', durationSec: 200 } });
  });
});
