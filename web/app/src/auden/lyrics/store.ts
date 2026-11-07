import { notifyStores } from '../db/notify';
import { openAudenDb, type LyricsVariantRow, type SongLyricsPrefRow } from '../db/schema';
import { toStoredProvider } from './names';
import { resultToVariant, variantToResult } from './variant';
import type { LyricsResult } from '@/lyrics/repository';
import type { LyricsSourceId } from '@/lyrics/sources';

/**
 * Saved lyrics, over `lyrics_variant` (one row per song and provider) and `song_lyrics_pref` (the
 * song's chosen provider and its own offset): LyricsStore.kt. Writes that touch both stores share one
 * transaction, so a delete never leaves a choice pointing at nothing.
 */

export interface SongPref {
  activeProvider: string | null;
  offsetMs: number | null;
}

const NONE: SongPref = { activeProvider: null, offsetMs: null };

export async function readPref(songKey: string): Promise<SongPref> {
  const db = await openAudenDb();
  const row = await db.get('song_lyrics_pref', songKey);
  return row ? { activeProvider: row.activeProvider, offsetMs: row.offsetMs } : NONE;
}

export async function listVariants(songKey: string): Promise<LyricsVariantRow[]> {
  const db = await openAudenDb();
  return db.getAll('lyrics_variant', IDBKeyRange.bound([songKey, ''], [songKey, []]));
}

/** The song's chosen variant, only if it is still stored (a choice with nothing behind it is no choice). */
export async function activeVariant(songKey: string): Promise<{ pref: SongPref; result: LyricsResult | null }> {
  const db = await openAudenDb();
  const tx = db.transaction(['lyrics_variant', 'song_lyrics_pref']);
  const pref = await tx.objectStore('song_lyrics_pref').get(songKey);
  const row = pref?.activeProvider ? await tx.objectStore('lyrics_variant').get([songKey, pref.activeProvider]) : undefined;
  await tx.done;
  return {
    pref: pref ? { activeProvider: pref.activeProvider, offsetMs: pref.offsetMs } : NONE,
    result: row ? variantToResult(row) : null,
  };
}

async function patchPref(songKey: string, patch: Partial<SongPref>) {
  const db = await openAudenDb();
  const tx = db.transaction('song_lyrics_pref', 'readwrite');
  const current: SongLyricsPrefRow = (await tx.store.get(songKey)) ?? { songKey, activeProvider: null, offsetMs: null };
  const next = { ...current, ...patch, songKey };
  // A row with nothing in it is not kept.
  if (next.activeProvider === null && next.offsetMs === null) await tx.store.delete(songKey);
  else await tx.store.put(next);
  await tx.done;
  notifyStores('song_lyrics_pref');
}

/** The provider this song shows (null = "Best available"). The variant need not exist yet. */
export function setActiveProvider(songKey: string, provider: LyricsSourceId | null): Promise<void> {
  return patchPref(songKey, { activeProvider: provider === null ? null : toStoredProvider(provider) });
}

/** This song's own offset in ms; null makes it follow the global one again. */
export function setSongOffset(songKey: string, offsetMs: number | null): Promise<void> {
  return patchPref(songKey, { offsetMs });
}

/** Keeps [result] for the song; [makeActive] also makes it the one shown. */
export async function saveVariant(songKey: string, result: LyricsResult, { makeActive }: { makeActive: boolean }): Promise<void> {
  const row = resultToVariant(songKey, result);
  const db = await openAudenDb();
  const tx = db.transaction(['lyrics_variant', 'song_lyrics_pref'], 'readwrite');
  await tx.objectStore('lyrics_variant').put(row);
  if (makeActive) {
    const prefs = tx.objectStore('song_lyrics_pref');
    const current = (await prefs.get(songKey)) ?? { songKey, activeProvider: null, offsetMs: null };
    await prefs.put({ ...current, activeProvider: row.provider });
  }
  await tx.done;
  notifyStores('lyrics_variant', 'song_lyrics_pref');
}

/** Deletes one provider's variant, and clears the song's choice if it pointed at it. */
export async function removeVariant(songKey: string, provider: LyricsSourceId | string): Promise<void> {
  const stored = toStoredProviderLoose(provider);
  const db = await openAudenDb();
  const tx = db.transaction(['lyrics_variant', 'song_lyrics_pref'], 'readwrite');
  await tx.objectStore('lyrics_variant').delete([songKey, stored]);
  const prefs = tx.objectStore('song_lyrics_pref');
  const pref = await prefs.get(songKey);
  if (pref?.activeProvider === stored) {
    if (pref.offsetMs === null) await prefs.delete(songKey);
    else await prefs.put({ ...pref, activeProvider: null });
  }
  await tx.done;
  notifyStores('lyrics_variant', 'song_lyrics_pref');
}

/** A web id or an already-stored (Android) name, whichever the caller has. */
function toStoredProviderLoose(provider: string): string {
  try {
    return toStoredProvider(provider as LyricsSourceId) ?? provider;
  } catch {
    return provider;
  }
}
