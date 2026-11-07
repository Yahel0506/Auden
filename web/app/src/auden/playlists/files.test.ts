import { describe, expect, it } from 'vitest';
import {
  AudenJson,
  isUrl,
  M3u,
  MAX_PLAYLIST_ENTRIES,
  type PlaylistEntry,
  PlaylistFormat,
  Paths,
  parsePlaylist,
  sanitizeVideoId,
  Xspf,
} from './files';

const VIDEO_ID = 'dQw4w9WgXcQ';

const entries: PlaylistEntry[] = [
  { path: '/music/Rock/a.mp3', title: 'Song A', artist: 'Band', durationSec: 215 },
  { path: '/music/Pop/b & c.flac', title: 'B', artist: 'Singer', durationSec: null },
];

const MIB = 1024 * 1024;

describe('M3u', () => {
  it('round-trips relative to its own folder', () => {
    const text = M3u.write('Mix', entries, '/music/Playlists');
    expect(text).toContain('../Rock/a.mp3');
    expect(text.startsWith('#EXTM3U')).toBe(true);
    const back = M3u.parse(text, '/music/Playlists');
    expect(back.name).toBe('Mix');
    expect(back.entries.map((e) => e.path)).toEqual(entries.map((e) => e.path));
    expect(back.entries[0]?.artist).toBe('Band');
    expect(back.entries[0]?.title).toBe('Song A');
    expect(back.entries[0]?.durationSec).toBeCloseTo(215, 2);
  });

  it('reads Windows paths and URLs', () => {
    const parsed = M3u.parse('#EXTM3U\r\nsub\\x.mp3\r\nhttps://h/x.mp3\r\n', '/m');
    expect(parsed.entries[0]?.path).toBe('/m/sub/x.mp3');
    expect(isUrl(parsed.entries[1]?.path)).toBe(true);
  });

  it('strips a byte-order mark and accepts CRLF and bare CR line endings', () => {
    const parsed = M3u.parse('\uFEFF#EXTM3U\r\n#PLAYLIST: Late\r\n/a.mp3\r\n/b.mp3\r/c.mp3', null);
    expect(parsed.name).toBe('Late');
    expect(parsed.entries.map((e) => e.path)).toEqual(['/a.mp3', '/b.mp3', '/c.mp3']);
  });

  it('decodes file:// URLs with %20 and keeps a literal plus', () => {
    const parsed = M3u.parse('file:///music/My%20Song+1.mp3\n', '/elsewhere');
    expect(parsed.entries[0]?.path).toBe('/music/My Song+1.mp3');
  });

  it('keeps malformed percent escapes instead of throwing', () => {
    const parsed = M3u.parse('/music/100%zz.mp3\n', null);
    expect(parsed.entries[0]?.path).toBe('/music/100%zz.mp3');
  });

  it('splits the label on the first " - " and reads the length', () => {
    const parsed = M3u.parse('#EXTM3U\n#EXTINF:187,Artist - Title - Live\n/x.mp3\n', null);
    expect(parsed.entries[0]).toMatchObject({ artist: 'Artist', title: 'Title - Live', durationSec: 187 });
  });

  it('skips entries without a path when writing', () => {
    const text = M3u.write(null, [{ path: null, title: 'Gone' }, { path: '/a.mp3' }], null);
    expect(text).not.toContain('Gone');
    expect(M3u.parse(text, null).entries.map((e) => e.path)).toEqual(['/a.mp3']);
  });
});

describe('Xspf', () => {
  it('round-trips and escapes', () => {
    const text = Xspf.write('A & B', entries, '/music/Playlists');
    const back = Xspf.parse(text, '/music/Playlists');
    expect(back.name).toBe('A & B');
    expect(back.entries.map((e) => e.path)).toEqual(entries.map((e) => e.path));
    expect(back.entries[0]?.durationSec).toBeCloseTo(215, 2);
  });

  it('escapes angle brackets and quotes in titles', () => {
    const title = 'Tag <b> "quoted" & more';
    const back = Xspf.parse(Xspf.write(null, [{ path: '/a.mp3', title }], null), null);
    expect(back.entries[0]?.title).toBe(title);
  });

  it('reads CDATA sections', () => {
    const text = '<playlist><trackList><track><location>/a.mp3</location>' +
      '<title><![CDATA[Fish & <Chips>]]></title></track></trackList></playlist>';
    expect(Xspf.parse(text, null).entries[0]?.title).toBe('Fish & <Chips>');
  });

  it('decodes numeric references in range', () => {
    const text = '<playlist><trackList><track><location>/a.mp3</location>' +
      '<title>&#65;&#x42;&#x1F600;</title></track></trackList></playlist>';
    expect(Xspf.parse(text, null).entries[0]?.title).toBe('AB\u{1F600}');
  });

  it('leaves an out-of-range numeric reference as written and does not throw', () => {
    const text = '<playlist><trackList><track><location>/a.mp3</location>' +
      '<title>x&#x110000;y&#99999999999999999999999;z&#0;</title></track></trackList></playlist>';
    expect(() => Xspf.parse(text, null)).not.toThrow();
    expect(Xspf.parse(text, null).entries[0]?.title).toBe('x&#x110000;y&#99999999999999999999999;z&#0;');
  });

  it('does not decode an entity that an earlier entity produced', () => {
    const text = '<playlist><trackList><track><location>/a.mp3</location>' +
      '<title>&amp;lt;</title></track></trackList></playlist>';
    expect(Xspf.parse(text, null).entries[0]?.title).toBe('&lt;');
  });

  it('reads a track written in upper case tags', () => {
    const text = '<PLAYLIST><TRACKLIST><TRACK><LOCATION>/a.mp3</LOCATION></TRACK></TRACKLIST></PLAYLIST>';
    expect(Xspf.parse(text, null).entries.map((e) => e.path)).toEqual(['/a.mp3']);
  });
});

describe('AudenJson', () => {
  it('keeps video ids and writes the format version', () => {
    const text = AudenJson.write('Streamed', [{ path: null, title: 'T', artist: 'A', videoId: VIDEO_ID }]);
    expect(JSON.parse(text).auden).toBe(1);
    expect(AudenJson.parse(text)?.entries[0]?.videoId).toBe(VIDEO_ID);
    expect(parsePlaylist('x.json', text, null)?.entries[0]?.videoId).toBe(VIDEO_ID);
  });

  it('accepts a file without the version field', () => {
    const parsed = AudenJson.parse('{"name":"Old","tracks":[{"title":"T","path":"/a.mp3"}]}');
    expect(parsed).toEqual({ name: 'Old', entries: [{ path: '/a.mp3', title: 'T', artist: null, album: null, durationSec: null, videoId: null }] });
  });

  it('drops hostile video ids', () => {
    const hostile = ['spotify:x', 'local:1', 'auden-file://abc', '../../etc/passwd', 'short', '', 'a'.repeat(12)];
    const text = JSON.stringify({ tracks: hostile.map((videoId) => ({ videoId, title: 'T' })) });
    const parsed = AudenJson.parse(text);
    expect(parsed?.entries.map((e) => e.videoId)).toEqual(hostile.map(() => null));
  });

  it('returns null for JSON of the wrong shape', () => {
    expect(AudenJson.parse('[1,2]')).toBeNull();
    expect(AudenJson.parse('{"tracks":null}')).toBeNull();
    expect(AudenJson.parse('{"tracks":[{"durationSec":"long"}]}')).toBeNull();
    expect(AudenJson.parse('{"auden":"1"}')).toBeNull();
  });

  it('strips a byte-order mark', () => {
    expect(AudenJson.parse('\uFEFF{"name":"B","tracks":[]}')).toEqual({ name: 'B', entries: [] });
  });
});

describe('Paths', () => {
  it('normalizes, joins and relativizes', () => {
    expect(Paths.normalize('/a/b/../c')).toBe('/a/c');
    expect(Paths.normalize('a\\b\\.\\c')).toBe('a/b/c');
    expect(Paths.join('/music/Playlists', '../Rock/a.mp3')).toBe('/music/Rock/a.mp3');
    expect(Paths.relativize('/a/b/c.mp3', '/a/x')).toBe('../b/c.mp3');
    expect(Paths.relativize('/a/b/c.mp3', '/a/b')).toBe('c.mp3');
  });

  it('reads file names and parents', () => {
    expect(Paths.fileName('C:\\Music\\x.mp3')).toBe('x.mp3');
    expect(Paths.fileName('plain.mp3')).toBe('plain.mp3');
    expect(Paths.parent('/a/b/c.mp3')).toBe('/a/b');
    expect(Paths.parent('c.mp3')).toBe('');
  });

  it('makes safe file names', () => {
    expect(Paths.safeFileName('a:b')).toBe('a_b');
    expect(Paths.safeFileName('  ..  ')).toBe('playlist');
    expect(Paths.safeFileName('a/b\u0001c')).toBe('a_b_c');
  });

  it('adds numbers until a name is free', () => {
    const taken = new Set(['Mix.m3u', 'Mix (2).m3u']);
    expect(Paths.uniqueName('Mix', 'm3u', (name) => taken.has(name))).toBe('Mix (3).m3u');
    expect(Paths.uniqueName('Mix', 'm3u', () => false)).toBe('Mix.m3u');
  });
});

describe('sanitizeVideoId', () => {
  it('accepts exactly eleven id characters', () => {
    expect(sanitizeVideoId(VIDEO_ID)).toBe(VIDEO_ID);
    expect(sanitizeVideoId('abc_-DEF123')).toBe('abc_-DEF123');
    expect(sanitizeVideoId('abc')).toBeNull();
    expect(sanitizeVideoId('dQw4w9WgXcQ\n')).toBeNull();
    expect(sanitizeVideoId('dQw4w9WgXc!')).toBeNull();
    expect(sanitizeVideoId('')).toBeNull();
    expect(sanitizeVideoId(null)).toBeNull();
  });
});

describe('parsePlaylist', () => {
  it('sniffs the format from content first, then from the file name', () => {
    expect(parsePlaylist('mix.m3u8', '{"tracks":[{"title":"J"}]}', null)?.entries[0]?.title).toBe('J');
    expect(parsePlaylist('mix.txt', '<playlist><trackList><track><location>/x.mp3</location></track></trackList></playlist>', null)?.entries[0]?.path).toBe('/x.mp3');
    expect(parsePlaylist('mix.XSPF', '/y.mp3\n', null)?.entries).toEqual([]);
    expect(parsePlaylist('mix.m3u', '/y.mp3\n', null)?.entries[0]?.path).toBe('/y.mp3');
  });

  it('returns null for an unreadable Auden file', () => {
    expect(parsePlaylist('x.auden.json', '{ broken', null)).toBeNull();
  });

  it('strips a byte-order mark before sniffing', () => {
    expect(parsePlaylist('x.m3u8', '\uFEFF  {"name":"K","tracks":[]}', null)).toEqual({ name: 'K', entries: [] });
  });

  it('never throws on 2 MiB of JSON- or XML-shaped garbage', () => {
    const junk = 'x'.repeat(2 * MIB);
    expect(parsePlaylist('g.json', `{${junk}`, null)).toBeNull();
    expect(parsePlaylist('g.xspf', `<${junk}`, null)).toEqual({ name: null, entries: [] });
    expect(parsePlaylist('g.xspf', `<track>${'<location>'.repeat(200_000)}`, null)?.entries).toEqual([]);
  });

  it('never throws on 2 MiB of binary-looking text', () => {
    let blob = '';
    for (let i = 0; i < 2 * MIB; i++) blob += String.fromCharCode((i * 7919) % 256);
    const parsed = parsePlaylist('g.m3u', blob, '/base');
    expect(parsed).not.toBeNull();
    expect(parsed!.entries.length).toBeLessThanOrEqual(MAX_PLAYLIST_ENTRIES);
  });

  it('caps parsed entries at 10000 in every format', () => {
    const lines = Array.from({ length: 10_005 }, (_, i) => `/m/${i}.mp3`).join('\n');
    expect(M3u.parse(lines, null).entries).toHaveLength(MAX_PLAYLIST_ENTRIES);

    const tracks = Array.from({ length: 10_005 }, (_, i) => `<track><location>/m/${i}.mp3</location></track>`).join('');
    expect(Xspf.parse(`<trackList>${tracks}</trackList>`, null).entries).toHaveLength(MAX_PLAYLIST_ENTRIES);

    const json = JSON.stringify({ tracks: Array.from({ length: 10_005 }, (_, i) => ({ path: `/m/${i}.mp3` })) });
    expect(AudenJson.parse(json)?.entries).toHaveLength(MAX_PLAYLIST_ENTRIES);
  });

  it('round-trips through every format', () => {
    const streamed: PlaylistEntry[] = [
      { path: '/music/a.mp3', title: 'Local', artist: 'Band', album: 'Album', durationSec: 200, videoId: null },
      { path: null, title: 'Streamed', artist: 'Singer', album: null, durationSec: 61, videoId: VIDEO_ID },
    ];
    const json = parsePlaylist('p.auden.json', AudenJson.write('P', streamed), null);
    expect(json?.name).toBe('P');
    expect(json?.entries).toEqual(streamed.map((e) => ({ ...e, path: e.path ?? null, album: e.album ?? null, videoId: e.videoId ?? null })));

    const xspf = parsePlaylist('p.xspf', Xspf.write('P', [streamed[0]!], null), null);
    expect(xspf?.name).toBe('P');
    expect(xspf?.entries[0]).toMatchObject({ path: '/music/a.mp3', title: 'Local', artist: 'Band', album: 'Album', durationSec: 200 });

    const m3u = parsePlaylist('p.m3u8', M3u.write('P', [streamed[0]!], null), null);
    expect(m3u?.name).toBe('P');
    expect(m3u?.entries[0]).toMatchObject({ path: '/music/a.mp3', title: 'Local', artist: 'Band', durationSec: 200 });
  });

  it('exposes the format descriptions', () => {
    expect(PlaylistFormat.M3U8).toEqual({ extension: 'm3u8', mime: 'audio/x-mpegurl' });
    expect(PlaylistFormat.XSPF).toEqual({ extension: 'xspf', mime: 'application/xspf+xml' });
    expect(PlaylistFormat.AUDEN).toEqual({ extension: 'auden.json', mime: 'application/json' });
  });
});
