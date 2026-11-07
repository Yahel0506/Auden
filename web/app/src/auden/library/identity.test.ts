import { describe, expect, it } from 'vitest';
import { type IdentitySong, normalizeKey, parseDurationText, sameSong } from './identity';

const song = (over: Partial<IdentitySong> = {}): IdentitySong => ({
  videoId: null,
  localUri: null,
  title: 'Song',
  artist: 'Artist',
  durationSec: null,
  ...over,
});

describe('normalizeKey', () => {
  it('lowercases, strips accents, bracketed credits and punctuation', () => {
    expect(normalizeKey('Café Déjà Vu (Remastered 2011)')).toBe('cafe deja vu');
    expect(normalizeKey('Song [feat. X]')).toBe('song');
    expect(normalizeKey('  Hello,   World!  ')).toBe('hello world');
    expect(normalizeKey('...')).toBe('');
  });
});

describe('parseDurationText', () => {
  it('reads m:ss and h:mm:ss', () => {
    expect(parseDurationText('3:45')).toBe(225);
    expect(parseDurationText('1:02:03')).toBe(3723);
    expect(parseDurationText(' 3:45 ')).toBe(225);
  });

  it('returns null for blank, malformed or zero durations', () => {
    expect(parseDurationText(null)).toBeNull();
    expect(parseDurationText(undefined)).toBeNull();
    expect(parseDurationText('')).toBeNull();
    expect(parseDurationText('  ')).toBeNull();
    expect(parseDurationText('3:')).toBeNull();
    expect(parseDurationText('a:bc')).toBeNull();
    expect(parseDurationText('0:00')).toBeNull();
  });
});

describe('sameSong', () => {
  it('matches on an equal non-blank videoId', () => {
    expect(sameSong(song({ videoId: 'abc', title: 'X', artist: 'Y' }), song({ videoId: 'abc', title: 'Z', artist: 'W' }))).toBe(true);
  });

  it('does not match on a blank videoId', () => {
    expect(sameSong(song({ videoId: ' ', title: 'X', artist: 'Y' }), song({ videoId: ' ', title: 'Z', artist: 'W' }))).toBe(false);
  });

  it('matches on an equal localUri', () => {
    expect(sameSong(song({ localUri: 'content://1', title: 'X', artist: 'Y' }), song({ localUri: 'content://1', title: 'Z', artist: 'W' }))).toBe(true);
  });

  it('does not match on a null localUri', () => {
    expect(sameSong(song({ localUri: null, title: 'X', artist: 'Y' }), song({ localUri: null, title: 'Z', artist: 'W' }))).toBe(false);
  });

  it('matches on equal normalized titles with the same artist', () => {
    expect(sameSong(song({ title: 'Song (Remastered)' }), song({ title: 'song' }))).toBe(true);
  });

  it('rejects differing or empty normalized titles', () => {
    expect(sameSong(song({ title: 'Song' }), song({ title: 'Other' }))).toBe(false);
    expect(sameSong(song({ title: '!!!' }), song({ title: '!!!' }))).toBe(false);
  });

  it('requires non-empty artists that are equal or contain one another', () => {
    expect(sameSong(song({ artist: 'Artist' }), song({ artist: 'artist' }))).toBe(true);
    expect(sameSong(song({ artist: 'Artist' }), song({ artist: 'Artist feat. Guest' }))).toBe(true);
    expect(sameSong(song({ artist: 'Artist Guest' }), song({ artist: 'Artist' }))).toBe(true);
    expect(sameSong(song({ artist: 'Artist' }), song({ artist: 'Someone Else' }))).toBe(false);
    expect(sameSong(song({ artist: '' }), song({ artist: '' }))).toBe(false);
    expect(sameSong(song({ artist: 'Artist' }), song({ artist: '  ' }))).toBe(false);
  });

  it('requires durations within 3 seconds when both are known', () => {
    expect(sameSong(song({ durationSec: 200 }), song({ durationSec: 203 }))).toBe(true);
    expect(sameSong(song({ durationSec: 200 }), song({ durationSec: 204 }))).toBe(false);
  });

  it('ignores durations when either is unknown', () => {
    expect(sameSong(song({ durationSec: 200 }), song({ durationSec: null }))).toBe(true);
    expect(sameSong(song({ durationSec: null }), song({ durationSec: 999 }))).toBe(true);
  });
});
