// Port of dev/auden/library/io/PlaylistFiles.kt: the same formats and rules, plus the
// hardening the web build needs. Parsers never throw, video ids are validated and the
// entry count is capped. No DOM or Node APIs here.

/** One line of a playlist file, whatever format it came from. */
export interface PlaylistEntry {
  /** Where the file says the track is: absolute after parsing, as written after building. */
  path: string | null;
  title?: string | null;
  artist?: string | null;
  album?: string | null;
  durationSec?: number | null;
  videoId?: string | null;
}

export interface ParsedPlaylist {
  name: string | null;
  entries: PlaylistEntry[];
}

/** Entries beyond this count are ignored, so a hostile file cannot exhaust memory. */
export const MAX_PLAYLIST_ENTRIES = 10_000;

const BOM = '﻿';
const URL_SCHEME = /^[a-zA-Z][a-zA-Z0-9+.\-]+:\/\//;
const VIDEO_ID = /^[A-Za-z0-9_-]{11}$/;

/** The id when it is a YouTube video id, otherwise null (`spotify:…`, `local:…`, paths, blanks). */
export function sanitizeVideoId(id: string | null | undefined): string | null {
  return id != null && VIDEO_ID.test(id) ? id : null;
}

export function isUrl(path: string | null | undefined): boolean {
  return path != null && URL_SCHEME.test(path);
}

function stripBom(text: string): string {
  return text.startsWith(BOM) ? text.slice(1) : text;
}

function normalizePath(path: string): string {
  const absolute = path.startsWith('/');
  const out: string[] = [];
  for (const part of path.replace(/\\/g, '/').split('/')) {
    if (part === '' || part === '.') continue;
    if (part === '..') {
      if (out.length > 0 && out[out.length - 1] !== '..') out.pop();
      else if (!absolute) out.push('..');
    } else {
      out.push(part);
    }
  }
  return (absolute ? '/' : '') + out.join('/');
}

function joinPath(dir: string, relative: string): string {
  return normalizePath(`${dir}/${relative}`);
}

function fileNameOf(path: string): string {
  const slashed = path.replace(/\\/g, '/');
  return slashed.slice(slashed.lastIndexOf('/') + 1);
}

function parentOf(path: string): string {
  const slashed = path.replace(/\\/g, '/');
  const index = slashed.lastIndexOf('/');
  return index < 0 ? '' : slashed.slice(0, index);
}

function segments(path: string): string[] {
  return normalizePath(path)
    .replace(/^\/+|\/+$/g, '')
    .split('/')
    .filter((part) => part !== '');
}

/** [path] written relative to [dir] (`../Other/x.mp3` when it lives beside it). */
function relativizePath(path: string, dir: string): string {
  const from = segments(dir);
  const to = segments(path);
  let common = 0;
  while (common < from.length && common < to.length - 1 && from[common] === to[common]) common++;
  const ups = Array.from({ length: from.length - common }, () => '..');
  return [...ups, ...to.slice(common)].join('/');
}

/** A name every storage layer accepts: reserved characters become underscores. */
function safeFileName(name: string): string {
  const cleaned = name
    .replace(/[<>:"/\\|?*\x00-\x1F\x7F]/g, '_')
    .trim()
    .replace(/^\.+|\.+$/g, '');
  return cleaned === '' ? 'playlist' : cleaned;
}

/** [base] with " (2)", " (3)"… until [taken] stops objecting. */
function uniqueName(base: string, extension: string, taken: (name: string) => boolean): string {
  let candidate = `${base}.${extension}`;
  let n = 2;
  while (taken(candidate)) {
    candidate = `${base} (${n}).${extension}`;
    n++;
  }
  return candidate;
}

/** Path helpers that behave the same everywhere. */
export const Paths = {
  normalize: normalizePath,
  join: joinPath,
  fileName: fileNameOf,
  parent: parentOf,
  relativize: relativizePath,
  safeFileName,
  uniqueName,
};

/** Kotlin's Double.toLong: truncates, NaN becomes 0, out-of-range values saturate. */
function kotlinToLong(value: number): number {
  if (Number.isNaN(value)) return 0;
  return Math.min(Number.MAX_SAFE_INTEGER, Math.max(Number.MIN_SAFE_INTEGER, Math.trunc(value)));
}

/** Kotlin's String.toDoubleOrNull for the plain decimal forms playlists use; null otherwise. */
function parseDecimal(text: string): number | null {
  if (!/^[+-]?(?:\d+\.?\d*|\.\d+)(?:[eE][+-]?\d+)?[fFdD]?$/.test(text)) return null;
  const value = Number(text.replace(/[fFdD]$/, ''));
  return Number.isFinite(value) ? value : null;
}

function startsWithIgnoreCase(text: string, upperCasePrefix: string): boolean {
  return text.slice(0, upperCasePrefix.length).toUpperCase() === upperCasePrefix;
}

function splitLines(text: string): string[] {
  return text.split(/\r\n|\r|\n/);
}

/** Decodes percent escapes; malformed escapes leave the text as written instead of throwing. */
function safeDecode(text: string): string {
  try {
    return decodeURIComponent(text);
  } catch {
    return text;
  }
}

function present<T>(value: T | null | undefined): value is T {
  return value != null;
}

/** `.m3u` / `.m3u8`: one path per line, with `#EXTINF` giving length and "Artist - Title". */
export const M3u = {
  /**
   * Entries without a path are skipped, as in the Kotlin writer: a line needs a path.
   * Use Xspf or AudenJson when such entries must survive.
   */
  write(name: string | null, entries: PlaylistEntry[], baseDir: string | null): string {
    let out = '#EXTM3U\n';
    if (name != null) out += `#PLAYLIST:${name}\n`;
    for (const entry of entries) {
      const path = entry.path;
      if (path == null) continue;
      const label = [entry.artist, entry.title].filter(present).join(' - ');
      const seconds = entry.durationSec != null && entry.durationSec > 0 ? kotlinToLong(entry.durationSec) : -1;
      out += `#EXTINF:${seconds},${label.replace(/[\r\n]+/g, ' ')}\n`;
      out += `${baseDir != null && !isUrl(path) ? relativizePath(path, baseDir) : path}\n`;
    }
    return out;
  },

  /** Relative lines are resolved against [baseDir], the folder the file was read from. */
  parse(text: string, baseDir: string | null): ParsedPlaylist {
    let name: string | null = null;
    let info: string | null = null;
    const entries: PlaylistEntry[] = [];
    for (const raw of splitLines(stripBom(text))) {
      const line = raw.trim();
      if (line === '') continue;
      if (startsWithIgnoreCase(line, '#PLAYLIST:')) {
        name = line.slice('#PLAYLIST:'.length).trim() || null;
      } else if (startsWithIgnoreCase(line, '#EXTINF:')) {
        info = line.slice('#EXTINF:'.length);
      } else if (line.startsWith('#')) {
        continue;
      } else {
        const parsed = parseInfo(info);
        info = null;
        if (entries.length < MAX_PLAYLIST_ENTRIES) {
          entries.push({
            path: resolveLine(line, baseDir),
            title: parsed.title,
            artist: parsed.artist,
            durationSec: parsed.seconds,
          });
        }
      }
    }
    return { name, entries };
  },
};

function parseInfo(info: string | null): { seconds: number | null; artist: string | null; title: string | null } {
  if (info == null) return { seconds: null, artist: null, title: null };
  const comma = info.indexOf(',');
  const head = (comma < 0 ? info : info.slice(0, comma)).trim();
  const space = head.indexOf(' ');
  const parsedSeconds = parseDecimal(space < 0 ? head : head.slice(0, space));
  const seconds = parsedSeconds != null && parsedSeconds > 0 ? parsedSeconds : null;
  const label = comma < 0 ? '' : info.slice(comma + 1).trim();
  if (label === '') return { seconds, artist: null, title: null };
  const split = label.indexOf(' - ');
  if (split > 0) {
    return { seconds, artist: label.slice(0, split).trim(), title: label.slice(split + 3).trim() };
  }
  return { seconds, artist: null, title: label };
}

function resolveLine(line: string, baseDir: string | null): string {
  if (isUrl(line) && !line.startsWith('file://')) return line;
  const withoutScheme = line.startsWith('file://') ? line.slice('file://'.length) : line;
  const local = safeDecode(withoutScheme).replace(/\\/g, '/');
  if (local.startsWith('/') || baseDir == null || /^[A-Za-z]:\//.test(local)) return normalizePath(local);
  return joinPath(baseDir, local);
}

const XML_NAMED_ENTITIES = new Map<string, string>([
  ['lt', '<'],
  ['gt', '>'],
  ['quot', '"'],
  ['apos', "'"],
  ['amp', '&'],
]);
const XML_ENTITY = /&(?:#[xX]([0-9a-fA-F]+)|#([0-9]+)|(lt|gt|quot|apos|amp));/g;
const TRACK_OPEN = /<track>/gi;
const TRACK_CLOSE = /<\/track>/gi;

/** Whether a numeric character reference names a character that can be written out. */
function isCodePoint(value: number): boolean {
  return Number.isInteger(value) && value > 0 && value <= 0x10ffff && !(value >= 0xd800 && value <= 0xdfff);
}

function escapeXml(s: string): string {
  return s.replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;').replace(/"/g, '&quot;');
}

/**
 * Undoes CDATA markers and entities in one pass, so `&amp;lt;` becomes `&lt;` and
 * not `<`. Numeric references out of range or invalid are left as written.
 */
function unescapeXml(s: string): string {
  return s
    .replaceAll('<![CDATA[', '')
    .replaceAll(']]>', '')
    .replace(XML_ENTITY, (whole: string, hex?: string, dec?: string, named?: string) => {
      if (named !== undefined) return XML_NAMED_ENTITIES.get(named) ?? whole;
      const code = hex !== undefined ? parseInt(hex, 16) : parseInt(dec ?? '', 10);
      return isCodePoint(code) ? String.fromCodePoint(code) : whole;
    });
}

/** Text of the first `<name>…</name>` in [text], unescaped and trimmed; null when absent or blank. */
function tagText(text: string, name: string): string | null {
  const open = new RegExp(`<${name}>`, 'i').exec(text);
  if (!open) return null;
  const start = open.index + name.length + 2;
  const close = new RegExp(`</${name}>`, 'gi');
  close.lastIndex = start;
  const end = close.exec(text);
  if (!end) return null;
  return unescapeXml(text.slice(start, end.index)).trim() || null;
}

function indexAt(pattern: RegExp, text: string, from: number): number {
  pattern.lastIndex = from;
  const match = pattern.exec(text);
  return match ? match.index : -1;
}

function durationSecOf(text: string | null): number | null {
  const ms = text == null ? null : parseDecimal(text);
  const seconds = ms == null ? null : ms / 1000;
  return seconds != null && seconds > 0 ? seconds : null;
}

/** XSPF: XML playlists. Read with plain tag matching, never a DTD-aware parser. */
export const Xspf = {
  write(name: string | null, entries: PlaylistEntry[], baseDir: string | null): string {
    let out = '<?xml version="1.0" encoding="UTF-8"?>\n<playlist version="1" xmlns="http://xspf.org/ns/0/">\n';
    if (name != null) out += `  <title>${escapeXml(name)}</title>\n`;
    out += '  <trackList>\n';
    for (const entry of entries) {
      out += '    <track>\n';
      if (entry.path != null) {
        const location = baseDir != null && !isUrl(entry.path) ? relativizePath(entry.path, baseDir) : entry.path;
        out += `      <location>${escapeXml(location)}</location>\n`;
      }
      if (entry.title != null) out += `      <title>${escapeXml(entry.title)}</title>\n`;
      if (entry.artist != null) out += `      <creator>${escapeXml(entry.artist)}</creator>\n`;
      if (entry.album != null) out += `      <album>${escapeXml(entry.album)}</album>\n`;
      if (entry.durationSec != null) out += `      <duration>${kotlinToLong(entry.durationSec * 1000)}</duration>\n`;
      out += '    </track>\n';
    }
    out += '  </trackList>\n</playlist>\n';
    return out;
  },

  parse(text: string, baseDir: string | null): ParsedPlaylist {
    const body = stripBom(text);
    const listStart = body.search(/<tracklist/i);
    const name = tagText(listStart < 0 ? body : body.slice(0, listStart), 'title');
    const entries: PlaylistEntry[] = [];
    let pos = 0;
    while (entries.length < MAX_PLAYLIST_ENTRIES) {
      const open = indexAt(TRACK_OPEN, body, pos);
      if (open < 0) break;
      const start = open + '<track>'.length;
      const close = indexAt(TRACK_CLOSE, body, start);
      // No closing tag here means none follows either, so nothing later can match.
      if (close < 0) break;
      pos = close + '</track>'.length;
      const block = body.slice(start, close);
      const location = tagText(block, 'location');
      entries.push({
        path: location == null ? null : (M3u.parse(location, baseDir).entries[0]?.path ?? null),
        title: tagText(block, 'title'),
        artist: tagText(block, 'creator'),
        album: tagText(block, 'album'),
        durationSec: durationSecOf(tagText(block, 'duration')),
      });
    }
    return { name, entries };
  },
};

interface AudenTrack {
  videoId?: string;
  title?: string;
  artist?: string;
  album?: string;
  durationSec?: number;
  path?: string;
}

type JsonObject = Record<string, unknown>;

function isJsonObject(value: unknown): value is JsonObject {
  return typeof value === 'object' && value !== null && !Array.isArray(value);
}

function jsonString(value: unknown): string | null {
  if (value == null) return null;
  if (typeof value === 'string') return value;
  throw new TypeError('expected a string');
}

function jsonNumber(value: unknown): number | null {
  if (value == null) return null;
  if (typeof value === 'number') return value;
  throw new TypeError('expected a number');
}

function trackOf(entry: PlaylistEntry): AudenTrack {
  const track: AudenTrack = {};
  if (entry.videoId != null) track.videoId = entry.videoId;
  if (entry.title != null) track.title = entry.title;
  if (entry.artist != null) track.artist = entry.artist;
  if (entry.album != null) track.album = entry.album;
  if (entry.durationSec != null && Number.isFinite(entry.durationSec)) track.durationSec = entry.durationSec;
  if (entry.path != null) track.path = entry.path;
  return track;
}

function entryOfJson(raw: unknown): PlaylistEntry {
  if (!isJsonObject(raw)) throw new TypeError('track is not an object');
  return {
    path: jsonString(raw.path),
    title: jsonString(raw.title),
    artist: jsonString(raw.artist),
    album: jsonString(raw.album),
    durationSec: jsonNumber(raw.durationSec),
    videoId: sanitizeVideoId(jsonString(raw.videoId)),
  };
}

/** Auden's own format: lossless for streamed songs, which paths alone cannot describe. */
export const AudenJson = {
  write(name: string | null, entries: PlaylistEntry[]): string {
    const file = {
      auden: 1,
      ...(name != null ? { name } : {}),
      tracks: entries.map(trackOf),
    };
    return JSON.stringify(file, null, 4);
  },

  /** Null when the text is not a readable Auden playlist. Never throws. */
  parse(text: string): ParsedPlaylist | null {
    try {
      const value: unknown = JSON.parse(stripBom(text));
      if (!isJsonObject(value)) return null;
      const auden = value.auden;
      if (auden !== undefined && !(typeof auden === 'number' && Number.isInteger(auden))) return null;
      const tracks = value.tracks === undefined ? [] : value.tracks;
      if (!Array.isArray(tracks)) return null;
      return {
        name: jsonString(value.name),
        entries: tracks.slice(0, MAX_PLAYLIST_ENTRIES).map(entryOfJson),
      };
    } catch {
      return null;
    }
  },
};

export const PlaylistFormat = {
  /** What other players read. */
  M3U8: { extension: 'm3u8', mime: 'audio/x-mpegurl' },
  XSPF: { extension: 'xspf', mime: 'application/xspf+xml' },
  /** Auden's own, which keeps streamed songs that a path cannot describe. */
  AUDEN: { extension: 'auden.json', mime: 'application/json' },
} as const;

export type PlaylistFormat = (typeof PlaylistFormat)[keyof typeof PlaylistFormat];

/**
 * Picks the reader by content, falling back to the file name: `{` is Auden JSON,
 * `<` or a `.xspf` name is XSPF, anything else is M3U. Null when the file is
 * unreadable. Never throws.
 */
export function parsePlaylist(fileName: string, text: string, baseDir: string | null): ParsedPlaylist | null {
  try {
    const trimmed = stripBom(text).trimStart();
    if (trimmed.startsWith('{')) return AudenJson.parse(trimmed);
    if (trimmed.startsWith('<') || fileName.toLowerCase().endsWith('.xspf')) return Xspf.parse(trimmed, baseDir);
    return M3u.parse(trimmed, baseDir);
  } catch {
    return null;
  }
}
