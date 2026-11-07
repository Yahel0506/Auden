import { describe, expect, it } from 'vitest';
import { line, type LyricWord } from '@/lyrics/model';
import { parseLrc } from '@/lyrics/parse';
import { MAX_LYRICS_CHARS, toLrc } from './lrc';

const word = (startMs: number, endMs: number, text: string): LyricWord => ({ startMs, endMs, text, syllables: [] });

describe('toLrc', () => {
  it('writes line-synced lyrics as stamped lines, in time order', () => {
    const lines = [line(2500, 'World'), line(1000, 'Hello')];
    expect(toLrc(lines)).toBe('[00:01.00]Hello\n[00:02.50]World');
  });

  it('writes word-synced lines in Enhanced LRC', () => {
    const lines = [
      line(1000, 'Hello world', { words: [word(1000, 1500, 'Hello'), word(1500, 2000, 'world')] }),
      line(3000, 'Plain line'),
    ];
    expect(toLrc(lines)).toBe('[00:01.00]<00:01.00>Hello <00:01.50>world\n[00:03.00]Plain line');
  });

  it('writes untimed lyrics as plain text under a stamp, in their given order', () => {
    const lines = [line(0, 'First'), line(0, 'Second')];
    expect(toLrc(lines)).toBe('[00:00.00]First\n[00:00.00]Second');
  });

  it('writes an instrumental gap as an empty stamped line', () => {
    expect(toLrc([line(1000, 'A'), line(5000, '')])).toBe('[00:01.00]A\n[00:05.00]');
  });

  it('pads minutes, seconds and centiseconds, and carries minutes past an hour', () => {
    expect(toLrc([line(61_234, 'x'), line(3_723_999, 'y')])).toBe('[01:01.23]x\n[62:03.99]y');
  });

  it('writes no offset header at zero', () => {
    expect(toLrc([line(1000, 'Hello')], 0)).toBe('[00:01.00]Hello');
  });

  it('negates a positive app offset in the header, so lyrics come later', () => {
    expect(toLrc([line(1000, 'Hello')], 500)).toBe('[offset:-500]\n[00:01.00]Hello');
  });

  it('writes a negative app offset as a positive header, so lyrics come earlier', () => {
    expect(toLrc([line(1000, 'Hello')], -300)).toBe('[offset:300]\n[00:01.00]Hello');
  });

  it('leaves the line stamps unshifted by the offset', () => {
    expect(toLrc([line(1000, 'Hello')], 500)).toContain('[00:01.00]Hello');
  });

  it('writes title and artist tags ahead of the offset', () => {
    expect(toLrc([line(1000, 'Hello')], 500, { title: 'Song', artist: 'Band' })).toBe(
      '[ti:Song]\n[ar:Band]\n[offset:-500]\n[00:01.00]Hello',
    );
  });

  it('leaves out a blank tag', () => {
    expect(toLrc([line(1000, 'Hello')], 0, { title: '  ', artist: 'Band' })).toBe('[ar:Band]\n[00:01.00]Hello');
  });

  it('writes a background vocal as its own line at its own time', () => {
    const lines = [line(1000, 'Hello', { background: line(2000, '(ooh)') })];
    expect(toLrc(lines)).toBe('[00:01.00]Hello\n[00:02.00](ooh)');
  });

  it('writes a background vocal with word timings in Enhanced LRC', () => {
    const background = line(2000, '(ooh)', { words: [word(2000, 2500, '(ooh)')] });
    expect(toLrc([line(1000, 'Hello', { background })])).toBe('[00:01.00]Hello\n[00:02.00]<00:02.00>(ooh)');
  });

  it('cuts a document over the limit at the last whole line under it', () => {
    const text = 'x'.repeat(90);
    const lines = Array.from({ length: 1000 }, (_, i) => line(i * 1000, text));
    const out = toLrc(lines);
    // Each row is 100 characters and each separator one more: 633 rows come to 63,932, and 634 would pass 64,000.
    const rows = out.split('\n');
    expect(out.length).toBeLessThanOrEqual(MAX_LYRICS_CHARS);
    expect(rows).toHaveLength(633);
    expect(rows.every((row) => row.endsWith(text))).toBe(true);
    expect(rows.at(-1)).toBe(`[10:32.00]${text}`);
  });

  it('keeps a document exactly at the limit whole', () => {
    const lines = [line(0, 'x'.repeat(MAX_LYRICS_CHARS - 10))];
    expect(toLrc(lines)).toHaveLength(MAX_LYRICS_CHARS);
  });

  it('returns an empty string for no lines', () => {
    expect(toLrc([])).toBe('');
  });
});

describe('toLrc and parseLrc', () => {
  it('reads line-synced output back to the same times and texts', () => {
    // LRC stamps are centiseconds, so the times here are whole centiseconds.
    const lines = [line(1000, 'Hello'), line(2500, 'World'), line(61_230, 'Later on')];
    const parsed = parseLrc(toLrc(lines));
    expect(parsed.map((l) => [l.timeMs, l.text])).toEqual(lines.map((l) => [l.timeMs, l.text]));
  });
});
