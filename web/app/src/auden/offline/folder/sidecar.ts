import { effectiveOffset } from '../../lyrics/offset';
import { lyricsKeyFor } from '../../lyrics/key';
import { toLrc } from '../../lyrics/lrc';
import { activeVariant, listVariants } from '../../lyrics/store';
import { variantToResult } from '../../lyrics/variant';
import { useLyricsSettings } from '@/lyrics/settings';
import type { LyricsResult } from '@/lyrics/repository';
import type { OfflineItem } from '../types';

/**
 * The `.lrc` text for a kept song, from the Phase 5 store: the song's chosen version, else any saved one. Only synced
 * lyrics make an `.lrc` (plain text would come out stamped `[00:00.00]` on every line, which players would show as
 * a wall at the start). The song's own offset, else the global one, goes in as `[offset:]`.
 */
export async function lrcTextFor(item: Pick<OfflineItem, 'videoId' | 'title' | 'artist'>): Promise<string | null> {
  const key = await lyricsKeyFor(item.videoId);
  const { pref, result } = await activeVariant(key);
  let chosen: LyricsResult | null = result;
  if (!chosen) {
    const found = (await listVariants(key)).map((row) => variantToResult(row)).filter((r): r is LyricsResult => !!r);
    chosen = found.find((r) => r.synced) ?? null;
  }
  if (!chosen?.synced) return null;
  return toLrc(chosen.lines, effectiveOffset(pref.offsetMs, useLyricsSettings.getState().lyricsOffsetMs), { title: item.title, artist: item.artist });
}
