// @vitest-environment happy-dom
import { describe, expect, it } from 'vitest';
import { line } from '@/lyrics/model';
import type { LyricsResult } from '@/lyrics/repository';
import { writeTtml } from '@/lyrics/ttml';
import { MAX_LYRICS_CHARS } from './lrc';
import { resultToVariant, syncKind, variantToResult, VariantFormat } from './variant';

const word = (startMs: number, text: string) => ({ startMs, endMs: startMs + 400, text, syllables: [] });
const result = (lines = [line(1000, 'hello world'), line(5000, 'second line')], source: LyricsResult['source'] = 'lrclib'): LyricsResult => ({
  source,
  label: 'LRCLIB',
  lines,
  ttml: writeTtml(lines),
  wordSynced: lines.some((l) => l.words.length),
  synced: true,
});

describe('resultToVariant / variantToResult', () => {
  it('saves line-timed lyrics as LRC under Android\'s provider name and reads them back as the same lyrics', () => {
    const original = result();
    const row = resultToVariant('abcdefghijk', original, 123);
    expect(row).toMatchObject({ songKey: 'abcdefghijk', provider: 'LRCLIB', format: 'LRC', fetchedAt: 123 });
    expect(row.content.split('\n').every((l) => /^\[\d\d:\d\d\.\d\d\]/.test(l))).toBe(true);
    const back = variantToResult(row)!;
    expect(back.source).toBe('lrclib');
    expect(back.lines.map((l) => [l.timeMs, l.text])).toEqual(original.lines.map((l) => [l.timeMs, l.text]));
    expect(back.synced).toBe(true);
  });

  it('saves plain lyrics as LRC too: Android\'s TTML reader drops a line with no timing', () => {
    const plain = { ...result([line(0, 'no timing here'), line(0, 'nor here')], 'youtube-music'), synced: false };
    const row = resultToVariant('k', plain);
    expect(row.format).toBe('LRC');
    expect(row.content).toContain('[00:00.00]no timing here');
    const back = variantToResult(row)!;
    expect(back.lines.map((l) => l.text)).toEqual(['no timing here', 'nor here']);
    expect(back.synced).toBe(false);
  });

  it('keeps word timing through the round trip, as TTML', () => {
    const lines = [line(1000, 'hello world', { words: [word(1000, 'hello '), word(1500, 'world')] })];
    const row = resultToVariant('k', result(lines, 'lyrics-plus'));
    expect(row.format).toBe('TTML');
    const back = variantToResult(row)!;
    expect(back.wordSynced).toBe(true);
    expect(back.lines[0]!.words.map((w) => w.text.trim())).toEqual(['hello', 'world']);
  });

  it('reads the formats Android writes: LRC and enhanced LRC', () => {
    const lrc = variantToResult({ provider: 'LRCLIB', format: VariantFormat.LRC, content: '[00:01.00]one\n[00:05.50]two' })!;
    expect(lrc.lines.map((l) => [l.timeMs, l.text])).toEqual([[1000, 'one'], [5500, 'two']]);
    expect(lrc.wordSynced).toBe(false);
    const enhanced = variantToResult({ provider: 'LYRICS_PLUS', format: VariantFormat.ENHANCED_LRC, content: '[00:01.00]<00:01.00>hello <00:01.50>world' })!;
    expect(enhanced.wordSynced).toBe(true);
    // And the TTML the panel draws is built for them.
    expect(lrc.ttml).toContain('<tt');
  });

  it('still decodes a provider the web does not know, under its own label', () => {
    const back = variantToResult({ provider: 'SIMP_MUSIC', format: VariantFormat.LRC, content: '[00:01.00]one' })!;
    expect(back.label).toBe('Simp Music');
    expect(back.source).toBe('SIMP_MUSIC');
  });

  it('refuses a variant with no lyrics in it instead of showing an empty panel', () => {
    expect(variantToResult({ provider: 'LRCLIB', format: VariantFormat.LRC, content: '' })).toBeNull();
    expect(variantToResult({ provider: 'LRCLIB', format: VariantFormat.TTML, content: '<not-ttml' })).toBeNull();
  });

  it('keeps a result too long for a row as LRC instead of dropping it', () => {
    const lines = Array.from({ length: 2500 }, (_, i) => line(i * 1000, `a fairly long lyric line number ${i} that goes on`));
    const big = result(lines);
    expect(big.ttml.length).toBeGreaterThan(MAX_LYRICS_CHARS);
    const row = resultToVariant('k', big);
    expect(row.format).toBe(VariantFormat.LRC);
    expect(row.content.length).toBeLessThanOrEqual(MAX_LYRICS_CHARS);
  });

  it('names the timing a variant has', () => {
    expect(syncKind({ wordSynced: true, synced: true })).toBe('word');
    expect(syncKind({ wordSynced: false, synced: true })).toBe('line');
    expect(syncKind({ wordSynced: false, synced: false })).toBe('plain');
  });
});
