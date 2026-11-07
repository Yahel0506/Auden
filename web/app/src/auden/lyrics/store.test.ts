// @vitest-environment happy-dom
import 'fake-indexeddb/auto';
import { IDBFactory } from 'fake-indexeddb';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { line } from '@/lyrics/model';
import type { LyricsResult } from '@/lyrics/repository';
import { writeTtml } from '@/lyrics/ttml';
import { subscribeToStores } from '../db/notify';
import { closeAudenDb } from '../db/schema';
import { activeVariant, listVariants, readPref, removeVariant, saveVariant, setActiveProvider, setSongOffset } from './store';

const result = (source: LyricsResult['source'], text: string): LyricsResult => {
  const lines = [line(1000, text)];
  return { source, label: source, lines, ttml: writeTtml(lines), wordSynced: false, synced: true };
};

beforeEach(() => {
  globalThis.indexedDB = new IDBFactory();
});
afterEach(async () => {
  await closeAudenDb();
});

describe('saved lyrics', () => {
  it('keeps one variant per provider and lists a song\'s own', async () => {
    await saveVariant('v1', result('lrclib', 'a'), { makeActive: false });
    await saveVariant('v1', result('kugou', 'b'), { makeActive: false });
    await saveVariant('v2', result('lrclib', 'c'), { makeActive: false });
    expect((await listVariants('v1')).map((v) => v.provider).sort()).toEqual(['KUGOU', 'LRCLIB']);
    expect(await readPref('v1')).toEqual({ activeProvider: null, offsetMs: null });
  });

  it('makeActive chooses it for the song; the choice counts only while it is stored', async () => {
    await saveVariant('v1', result('lrclib', 'hello'), { makeActive: true });
    const { pref, result: shown } = await activeVariant('v1');
    expect(pref.activeProvider).toBe('LRCLIB');
    expect(shown?.lines[0]!.text).toBe('hello');
    // A choice with nothing behind it is no choice.
    await setActiveProvider('v3', 'kugou');
    expect((await activeVariant('v3')).result).toBeNull();
    expect((await activeVariant('v3')).pref.activeProvider).toBe('KUGOU');
  });

  it('a later save with makeActive false does not change which one is shown', async () => {
    await saveVariant('v1', result('lrclib', 'first'), { makeActive: true });
    await saveVariant('v1', result('kugou', 'extra'), { makeActive: false });
    expect((await activeVariant('v1')).result?.lines[0]!.text).toBe('first');
  });

  it('deleting the chosen variant clears the choice in the same step, and keeps the song\'s offset', async () => {
    await saveVariant('v1', result('lrclib', 'x'), { makeActive: true });
    await setSongOffset('v1', 300);
    await removeVariant('v1', 'lrclib');
    expect(await listVariants('v1')).toEqual([]);
    expect(await readPref('v1')).toEqual({ activeProvider: null, offsetMs: 300 });
  });

  it('deleting a variant that is not the chosen one leaves the choice alone', async () => {
    await saveVariant('v1', result('lrclib', 'x'), { makeActive: true });
    await saveVariant('v1', result('kugou', 'y'), { makeActive: false });
    await removeVariant('v1', 'kugou');
    expect((await readPref('v1')).activeProvider).toBe('LRCLIB');
  });

  it('a preference row with nothing in it is not kept', async () => {
    await setSongOffset('v1', 500);
    await setSongOffset('v1', null);
    expect(await readPref('v1')).toEqual({ activeProvider: null, offsetMs: null });
    const db = await (await import('../db/schema')).openAudenDb();
    expect(await db.count('song_lyrics_pref')).toBe(0);
  });

  it('tells other code which stores changed', async () => {
    const seen = vi.fn();
    const off = subscribeToStores(seen);
    await saveVariant('v1', result('lrclib', 'x'), { makeActive: true });
    off();
    expect(seen).toHaveBeenCalledWith(['lyrics_variant', 'song_lyrics_pref']);
  });
});
