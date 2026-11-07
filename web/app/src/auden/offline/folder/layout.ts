/**
 * Where a saved song goes inside the chosen folder, from a template: a port of Android's DownloadLayout.
 *
 * A template is a path with `{tokens}`: `{albumArtist}` `{artist}` `{album}` `{title}` `{track}` (two digits, empty when
 * unknown) and `{videoId}`. Whatever a token turns into is made safe as ONE path segment (a `/` in a name never makes
 * a folder), and what an empty token leaves behind is tidied (`" - Song"` becomes `"Song"`), so one template serves an
 * album track, a single and a song with half its tags missing.
 */

export const DEFAULT_TEMPLATE = '{album}/{artist} - {title}';
export const PRESETS: readonly { label: string; template: string }[] = [
  { label: 'Album folder', template: DEFAULT_TEMPLATE },
  { label: 'Artist / Album / Track', template: '{albumArtist}/{album}/{track} - {title}' },
  { label: 'Flat', template: '{artist} - {title}' },
];

export interface LayoutInfo {
  title: string;
  artist: string;
  /** The album's artist; the first credited artist when unknown. */
  albumArtist?: string | null;
  album: string | null;
  track: number | null;
  videoId: string;
}

export const SINGLES = 'Singles';
export const UNKNOWN_ARTIST = 'Unknown Artist';
/** A name is capped in UTF-8 bytes (ext4 and APFS allow 255 per name), leaving room for " (99)" and an extension. */
const MAX_SEGMENT_BYTES = 160;
const MAX_NAME_BYTES = 400;
const TOKEN = /\{(\w+)\}/g;
/** `year` and `disc` are known (a template pasted from Android stays valid) but this player has no value for them: always empty. */
const TOKENS = new Set(['title', 'artist', 'albumArtist', 'album', 'track', 'videoId', 'year', 'disc']);
// eslint-disable-next-line no-control-regex
const ILLEGAL = /[\\/:*?"<>|\u0000-\u001f]/g;
const WINDOWS_RESERVED = /^(con|prn|aux|nul|com[1-9]|lpt[1-9])$/i;

/** Whether the template can be used: known tokens only, a name in it, and nothing that climbs or starts at the root. */
export function validTemplate(template: string): boolean {
  const tokens = [...template.matchAll(TOKEN)].map((m) => m[1]!);
  return (
    tokens.every((t) => TOKENS.has(t)) &&
    (tokens.includes('title') || tokens.includes('videoId')) &&
    !template.startsWith('/') &&
    !template.endsWith('/') &&
    !template.includes('..') &&
    !template.includes('\\') &&
    !template.includes('//')
  );
}

/** The first credited artist, so "A" and "A & B" share one folder. */
export const firstArtist = (artist: string): string => artist.split(/,| & | feat\.? /i)[0]?.trim() || artist.trim();

const pad = (n: number | null) => (n && n > 0 ? String(n).padStart(2, '0') : '');
const tidy = (text: string) => text.trim().replace(/^[\s\-_.,]+/, '').replace(/[\s\-_.,]+$/, '').trim();

/** One path segment, safe on every filesystem the folder may live on (never empty, never `.`/`..`, no reserved device name). */
export function safeSegment(raw: string): string {
  let text = raw.replace(ILLEGAL, ' ').replace(/\s+/g, ' ').trim();
  // Names may not end in a dot or space (Windows) or begin with a dot (hidden, and "." / "..").
  text = text.replace(/[. ]+$/, '').replace(/^\.+/, '').trim();
  if (WINDOWS_RESERVED.test(text)) text = `${text}_`;
  return capBytes(text, MAX_SEGMENT_BYTES).trim();
}

const utf8 = (text: string): number => new TextEncoder().encode(text).length;

/** The longest prefix of [text] within [bytes] UTF-8 bytes, cut on a whole character (never half a surrogate pair). */
export function capBytes(text: string, bytes: number): string {
  let out = '';
  let used = 0;
  for (const char of text) {
    const size = utf8(char);
    if (used + size > bytes) break;
    out += char;
    used += size;
  }
  return out;
}

/** The path below the folder for this song, extension included, `/`-separated, with no two `/` in a row. */
export function relativeName(template: string, info: LayoutInfo, extension: string): string {
  const use = validTemplate(template) ? template : DEFAULT_TEMPLATE;
  const artist = info.artist.trim() || UNKNOWN_ARTIST;
  const values: Record<string, string> = {
    title: info.title.trim() || info.videoId,
    artist,
    albumArtist: info.albumArtist?.trim() || firstArtist(artist),
    album: info.album?.trim() || SINGLES,
    track: pad(info.track),
    videoId: info.videoId,
    year: '',
    disc: '',
  };
  const fill = (segment: string) => safeSegment(tidy(segment.replace(TOKEN, (_, name: string) => (values[name] ?? '').replace(/[\\/]/g, ' '))));
  const parts = use.split('/');
  const folders = parts.slice(0, -1).map(fill).filter(Boolean);
  const stem = fill(parts[parts.length - 1]!) || safeSegment(info.videoId) || 'track';
  const name = [...folders, `${stem}.${extension}`].join('/');
  if (utf8(name) <= MAX_NAME_BYTES) return name;
  return [...folders.slice(0, 2).map((f) => capBytes(f, 100).trim()), `${capBytes(stem, 120).trim()}.${extension}`].join('/');
}

export const folderOf = (path: string): string => (path.includes('/') ? path.slice(0, path.lastIndexOf('/')) : '');
export const fileNameOf = (path: string): string => path.slice(path.lastIndexOf('/') + 1);
export const stemOf = (path: string): string => fileNameOf(path).replace(/\.[^.]+$/, '');
/** Case- and normalisation-insensitive key: Windows and macOS see "É" and "é" (and NFC vs NFD) as one name. */
export const pathKey = (path: string): string => path.normalize('NFC').toLowerCase();

/** `Album/Song.webm` → `Album/Song (2).webm`. */
export function withSuffix(path: string, n: number): string {
  const dot = path.lastIndexOf('.');
  const slash = path.lastIndexOf('/');
  return dot > slash ? `${path.slice(0, dot)} (${n})${path.slice(dot)}` : `${path} (${n})`;
}

/** The extension a stored copy is written with (the file's own, from the OPFS name). */
export const extensionOf = (file: string): string => file.slice(file.lastIndexOf('.') + 1) || 'bin';

/** The settings preview. */
export const preview = (template: string): string =>
  relativeName(template, { title: 'Song Title', artist: 'Artist', album: 'Album Name', track: 3, videoId: 'dQw4w9WgXcQ' }, 'webm');
