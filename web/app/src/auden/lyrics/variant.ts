import { parseEnhancedLrc, parseLrc, parseTtml } from '@/lyrics/parse';
import { withBackgroundVocals } from '@/lyrics/model';
import type { LyricsResult } from '@/lyrics/repository';
import type { LyricsSourceId } from '@/lyrics/sources';
import { sourceById } from '@/lyrics/sources';
import { writeTtml } from '@/lyrics/ttml';
import type { LyricsVariantRow } from '../db/schema';
import { MAX_LYRICS_CHARS, toLrc } from './lrc';
import { fromStoredProvider, storedProviderLabel, toStoredProvider } from './names';

/** Android's LyricsFormat names (LyricsArtifact.kt). The web writes TTML, and reads all three. */
export const VariantFormat = { LRC: 'LRC', ENHANCED_LRC: 'ENHANCED_LRC', TTML: 'TTML' } as const;

/**
 * A saved variant as the panel shows it, built exactly as a fresh lookup builds its result
 * (repository.toResult). A provider name the web does not know still decodes, under its own label:
 * Android's decoder would drop it, and a backup should not lose lyrics the web merely cannot fetch.
 */
export function variantToResult(row: Pick<LyricsVariantRow, 'provider' | 'format' | 'content'>): LyricsResult | null {
  let parsed;
  try {
    parsed =
      row.format === VariantFormat.TTML
        ? parseTtml(row.content)
        : row.format === VariantFormat.ENHANCED_LRC
          ? parseEnhancedLrc(row.content)
          : parseLrc(row.content);
  } catch {
    return null;
  }
  const lines = withBackgroundVocals(parsed);
  if (!lines.some((l) => l.text.trim())) return null;
  const id = fromStoredProvider(row.provider);
  return {
    source: (id ?? row.provider) as LyricsSourceId,
    label: id ? (sourceById(id)?.label ?? id) : storedProviderLabel(row.provider),
    lines,
    ttml: row.format === VariantFormat.TTML ? row.content : writeTtml(lines),
    wordSynced: lines.some((l) => l.words.length),
    synced: lines.some((l) => l.timeMs > 0 || l.words.length),
  };
}

/**
 * What is written for a lookup's result, in the formats Android reads back (LyricsSerializer.fromLines):
 * word-timed lyrics as TTML, which keeps everything the panel draws; line-timed and plain lyrics as LRC.
 * Android's TTML reader drops a line with no timing, so plain lyrics written as TTML would be lost
 * in a backup. A result too long for a row (64,000 characters) falls back to enhanced LRC or LRC,
 * which is far smaller.
 */
export function resultToVariant(songKey: string, result: LyricsResult, now = Date.now()): LyricsVariantRow {
  const base = { songKey, provider: toStoredProvider(result.source), fetchedAt: now };
  if (result.wordSynced && result.ttml.length <= MAX_LYRICS_CHARS) return { ...base, format: VariantFormat.TTML, content: result.ttml };
  return {
    ...base,
    format: result.wordSynced ? VariantFormat.ENHANCED_LRC : VariantFormat.LRC,
    content: toLrc(result.lines),
  };
}

/** What kind of timing a variant has, for the saved-lyrics list. */
export function syncKind(result: Pick<LyricsResult, 'wordSynced' | 'synced'>): 'word' | 'line' | 'plain' {
  return result.wordSynced ? 'word' : result.synced ? 'line' : 'plain';
}
