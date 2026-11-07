import { describe, expect, it } from 'vitest';
import { artKeyFromUrl, artKeyOf, artUrlOf, isAudioName, needsParse, planGone, plausiblySameFolder, relPathOf } from './diff';

describe('needsParse', () => {
  it('parses new files and files whose size or mtime moved, and skips unchanged ones', () => {
    expect(needsParse(undefined, 10, 5)).toBe(true);
    expect(needsParse({ size: 10, modifiedAt: 5 }, 10, 5)).toBe(false);
    expect(needsParse({ size: 10, modifiedAt: 5 }, 11, 5)).toBe(true);
    expect(needsParse({ size: 10, modifiedAt: 5 }, 10, 6)).toBe(true);
  });
});

describe('planGone', () => {
  it('lists the known paths a complete walk did not see', () => {
    expect(planGone(['a.mp3', 'b.mp3', 'c/d.flac'], ['a.mp3', 'c/d.flac'], true)).toEqual(['b.mp3']);
  });
  it('removes nothing after an incomplete walk (an unreadable subfolder looks like an empty one)', () => {
    expect(planGone(['a.mp3', 'c/d.flac'], ['a.mp3'], false)).toEqual([]);
  });
  it('removes nothing when the walk found no files but the index has some', () => {
    expect(planGone(['a.mp3'], [], true)).toEqual([]);
    expect(planGone([], [], true)).toEqual([]);
  });
});

describe('names and paths', () => {
  it('recognises audio files by extension, case-insensitively', () => {
    expect(isAudioName('x.MP3')).toBe(true);
    expect(isAudioName('x.opus')).toBe(true);
    expect(isAudioName('cover.jpg')).toBe(false);
    expect(isAudioName('.mp3')).toBe(false);
    expect(isAudioName('noext')).toBe(false);
  });
  it('drops the picked folder’s own name from a webkitRelativePath', () => {
    expect(relPathOf('Music/Rock/a.mp3')).toEqual({ root: 'Music', relPath: 'Rock/a.mp3' });
    expect(relPathOf('a.mp3')).toBeNull();
  });
  it('keys covers by album, else by folder, and round-trips the row url', () => {
    expect(artKeyOf({ album: 'OK Computer', albumArtist: 'Radiohead', artist: 'Radiohead' }, 'a/b.mp3')).toBe(
      artKeyOf({ album: 'ok computer', albumArtist: null, artist: 'RADIOHEAD' }, 'z/q.mp3'),
    );
    expect(artKeyOf(null, 'a/b/c.mp3')).toBe('folder:a/b');
    const key = artKeyOf({ album: 'X & Y', albumArtist: null, artist: 'C' }, 'p.mp3');
    expect(artKeyFromUrl(artUrlOf(key))).toBe(key);
    expect(artKeyFromUrl('https://x/y.jpg')).toBeNull();
  });
});

describe('plausiblySameFolder (picked folders have no handle, only a name)', () => {
  const known = ['a.mp3', 'b.mp3', 'c.mp3', 'd.mp3'];
  it('trusts a pick that shares at least half of the known paths', () => {
    expect(plausiblySameFolder(known, ['a.mp3', 'b.mp3', 'new.mp3'])).toBe(true);
    expect(plausiblySameFolder([], ['x.mp3'])).toBe(true);
  });
  it('does not trust another folder with the same name: no overlap, or a sliver', () => {
    expect(plausiblySameFolder(known, ['x.mp3', 'y.mp3'])).toBe(false);
    expect(plausiblySameFolder(known, ['a.mp3'])).toBe(false);
    expect(plausiblySameFolder(known, [])).toBe(false);
  });
  it('so a wrong pick removes nothing', () => {
    expect(planGone(known, ['x.mp3'], plausiblySameFolder(known, ['x.mp3']))).toEqual([]);
  });
});
