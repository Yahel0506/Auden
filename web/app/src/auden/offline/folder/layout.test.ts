import { describe, expect, it } from 'vitest';
import { capBytes, DEFAULT_TEMPLATE, extensionOf, fileNameOf, firstArtist, folderOf, pathKey, preview, relativeName, safeSegment, stemOf, validTemplate, withSuffix } from './layout';

const info = (over: Partial<Parameters<typeof relativeName>[1]> = {}) => ({ title: 'Song', artist: 'Artist', album: 'Album', track: 3, videoId: 'abcdefghijk', ...over });

describe('validTemplate', () => {
  it('accepts known tokens with a name in them', () => {
    expect(validTemplate(DEFAULT_TEMPLATE)).toBe(true);
    expect(validTemplate('{albumArtist}/{album}/{track} - {title}')).toBe(true);
    expect(validTemplate('{videoId}')).toBe(true);
  });
  it('refuses unknown tokens, no name, climbing, absolute and doubled separators', () => {
    for (const bad of ['{nope}/{title}', '{album}/{artist}', '../{title}', '{album}/../{title}', '/{title}', '{album}/', '{album}\\{title}', '{album}//{title}', '']) {
      expect(validTemplate(bad), bad).toBe(false);
    }
  });
});

describe('relativeName', () => {
  it('fills the default template, album as a folder', () => {
    expect(relativeName(DEFAULT_TEMPLATE, info(), 'webm')).toBe('Album/Artist - Song.webm');
  });
  it('makes a "/" in a name one segment, not two folders', () => {
    expect(relativeName(DEFAULT_TEMPLATE, info({ artist: 'AC/DC', album: 'Back/In Black' }), 'webm')).toBe('Back In Black/AC DC - Song.webm');
  });
  it('a token cannot climb out of the folder', () => {
    expect(relativeName('{album}/{title}', info({ album: '..', title: '../../etc/passwd' }), 'webm')).toBe('etc passwd.webm');
    expect(relativeName('{album}/{title}', info({ album: '...', title: '..' }), 'webm')).toBe('abcdefghijk.webm');
  });
  it('uses Singles and Unknown Artist when tags are missing, and tidies what an empty token leaves', () => {
    expect(relativeName(DEFAULT_TEMPLATE, info({ album: null, artist: ' ' }), 'm4a')).toBe('Singles/Unknown Artist - Song.m4a');
    expect(relativeName('{albumArtist}/{album}/{track} - {title}', info({ track: null }), 'webm')).toBe('Artist/Album/Song.webm');
    expect(relativeName('{albumArtist}/{album}/{track} - {title}', info(), 'webm')).toBe('Artist/Album/03 - Song.webm');
  });
  it('falls back to the default for an invalid template, and to the id for a blank title', () => {
    expect(relativeName('{x}/{title}', info(), 'webm')).toBe('Album/Artist - Song.webm');
    expect(relativeName('{title}', info({ title: '  ' }), 'webm')).toBe('abcdefghijk.webm');
  });
  it('strips illegal characters, trailing dots, and Windows device names', () => {
    expect(safeSegment('What?: <Now>*')).toBe('What Now');
    expect(safeSegment('Song...')).toBe('Song');
    expect(safeSegment('.hidden')).toBe('hidden');
    expect(safeSegment('CON')).toBe('CON_');
    expect(safeSegment('com1')).toBe('com1_');
    expect(relativeName('{title}', info({ title: 'NUL' }), 'webm')).toBe('NUL_.webm');
  });
  it('caps long names', () => {
    const long = 'x'.repeat(300);
    const path = relativeName(DEFAULT_TEMPLATE, info({ title: long, album: long, artist: long }), 'webm');
    expect(new TextEncoder().encode(path).length).toBeLessThanOrEqual(400);
    expect(path.endsWith('.webm')).toBe(true);
    expect(path.split('/').every((s) => s.length <= 165)).toBe(true);
  });
});

describe('helpers', () => {
  it('splits and suffixes paths', () => {
    expect(folderOf('A/B.webm')).toBe('A');
    expect(folderOf('B.webm')).toBe('');
    expect(fileNameOf('A/B.webm')).toBe('B.webm');
    expect(stemOf('A/B.c.webm')).toBe('B.c');
    expect(withSuffix('A/B.webm', 2)).toBe('A/B (2).webm');
    expect(withSuffix('A.b/B', 2)).toBe('A.b/B (2)');
    expect(pathKey('A/B.WEBM')).toBe('a/b.webm');
    expect(extensionOf('abc.webm')).toBe('webm');
    expect(firstArtist('A & B')).toBe('A');
    expect(firstArtist('A, B')).toBe('A');
    expect(preview(DEFAULT_TEMPLATE)).toBe('Album Name/Artist - Song Title.webm');
  });

  it('caps by UTF-8 bytes on whole characters, never splitting a surrogate pair', () => {
    expect(new TextEncoder().encode(capBytes('漢'.repeat(100), 160)).length).toBeLessThanOrEqual(160);
    expect(capBytes('漢'.repeat(100), 160)).toBe('漢'.repeat(53));
    expect(capBytes('😀'.repeat(50), 10)).toBe('😀😀');
    const path = relativeName(DEFAULT_TEMPLATE, info({ title: '漢'.repeat(200), artist: 'A', album: 'B' }), 'webm');
    for (const segment of path.split('/')) expect(new TextEncoder().encode(segment).length).toBeLessThan(250);
  });
  it('treats NFC and NFD, and case, as one name; year and disc are accepted and empty', () => {
    expect(pathKey('Caf\u00e9/X.webm')).toBe(pathKey('CAFE\u0301/x.WEBM'));
    expect(validTemplate('{year} {album}/{disc}{title}')).toBe(true);
    expect(relativeName('{album}/{disc}-{title}', info(), 'webm')).toBe('Album/Song.webm');
    expect(relativeName('{albumArtist}/{title}', info({ artist: 'A & B', albumArtist: 'Alb Artist' }), 'webm')).toBe('Alb Artist/Song.webm');
  });
});
