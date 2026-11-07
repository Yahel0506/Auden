import { isWordSynced, type LyricLine } from '@/lyrics/model';

/** LyricsSerializer.MAX_LYRICS_CHARS: a longer document is cut at the last whole line under it. */
export const MAX_LYRICS_CHARS = 64_000;

/** `mm:ss.xx` in centiseconds, padded with ASCII digits as LyricsSerializer.stampInner does. */
function clock(timeMs: number): string {
  const total = Math.max(0, Math.trunc(timeMs));
  const minutes = String(Math.floor(total / 60_000)).padStart(2, '0');
  const seconds = String(Math.floor((total % 60_000) / 1_000)).padStart(2, '0');
  const centiseconds = String(Math.floor((total % 1_000) / 10)).padStart(2, '0');
  return `${minutes}:${seconds}.${centiseconds}`;
}

/** A word-synced line is written word by word (Enhanced LRC); anything else as one stamped line. */
function writeLine(line: LyricLine): string {
  const stamp = `[${clock(line.timeMs)}]`;
  if (!isWordSynced(line)) return stamp + line.text;
  return stamp + line.words.map((word) => `<${clock(word.startMs)}>${word.text}`).join(' ');
}

/**
 * The answering vocal goes out as a line of its own at its own time, since
 * LRC has nowhere to put it beside the lead. Not nested: a background's
 * background is never written.
 */
function timedLines(lines: LyricLine[]): LyricLine[] {
  return lines.flatMap((l) => (l.background ? [l, l.background] : [l])).sort((a, b) => a.timeMs - b.timeMs);
}

/** A metadata tag, or nothing when the value is absent or blank. */
function tag(name: string, value: string | undefined): string[] {
  const clean = value?.replace(/[\r\n]+/g, ' ').trim();
  return clean ? [`[${name}:${clean}]`] : [];
}

/**
 * Writes the lines as LRC, Enhanced where a line has word timings (ports
 * LyricsSerializer.toLrc). The offset uses the app's convention, where a
 * positive value makes lyrics come later; LRC's [offset] does the opposite,
 * so the header carries the negation and the stamps stay as they are.
 *
 * Never throws: a document over MAX_LYRICS_CHARS keeps the lines that fit.
 */
export function toLrc(lines: LyricLine[], offsetMs = 0, header?: { title?: string; artist?: string }): string {
  const headerRows = [
    ...tag('ti', header?.title),
    ...tag('ar', header?.artist),
    ...(offsetMs !== 0 ? [`[offset:${Math.round(-offsetMs)}]`] : []),
  ];
  const rows = [...headerRows, ...timedLines(lines).map(writeLine)];

  const kept: string[] = [];
  let length = 0;
  for (const row of rows) {
    const next = length + (kept.length ? 1 : 0) + row.length;
    if (next > MAX_LYRICS_CHARS) break;
    kept.push(row);
    length = next;
  }
  return kept.join('\n');
}
