import { describe, expect, it } from 'vitest';
import androidGolden from './fixtures/android-golden.json';
import bitchordV1 from './fixtures/bitchord-v1.json';
import webBorn from './fixtures/web-born.json';
import { BackupError, parseBackup, suggestedBackupName } from './format';

const NOW = 1_770_000_000_000;

const NOT_A_BACKUP = 'That doesn’t look like an Auden backup.';

/** The message of the refusal [text] gets; fails the test if it is accepted or refused with another error type. */
function refusalOf(text: string): string {
  try {
    parseBackup(text, NOW);
  } catch (err) {
    expect(err).toBeInstanceOf(BackupError);
    return (err as BackupError).message;
  }
  throw new Error('the backup was accepted');
}

const track = (over: Record<string, unknown> = {}) => ({ id: 1, title: 'Song', artist: 'Artist', ...over });
const libraryWith = (library: Record<string, unknown>) => JSON.stringify({ app: 'auden', version: 2, library });
const withTracks = (...tracks: unknown[]) => libraryWith({ tracks });
const bucket = (over: Record<string, unknown> = {}) => ({ version: 2, month: '2026-11', ...over });
const withListening = (...buckets: unknown[]) => JSON.stringify({ app: 'auden', version: 2, listening: buckets });

describe('parseBackup on the Android golden file', () => {
  const file = parseBackup(JSON.stringify(androidGolden), NOW);
  const library = file.library!;

  it('reads the header as written', () => {
    expect(file.app).toBe('auden');
    expect(file.version).toBe(2);
    expect(file.versionName).toBe('1.11.0');
    expect(file.exportedAt).toBe('2026-11-02T10:15:30Z');
    expect(file.origin).toBeUndefined();
    expect(file.settingsCapturedAt).toBeUndefined();
  });

  it('keeps the settings verbatim, one of each type', () => {
    expect(file.settings).toEqual(androidGolden.settings);
    expect(Object.values(file.settings).map((p) => p.type).sort()).toEqual(['bool', 'float', 'int', 'long', 'string', 'stringSet']);
    expect(file.settings.search_history).toEqual({ type: 'stringSet', value: null, values: ['radiohead', 'lo-fi beats', 'bonobo'] });
  });

  it('keeps both listening months', () => {
    expect(file.listening.map((b) => b.month)).toEqual(['2026-10', '2026-09']);
    const oct = file.listening[0]!;
    expect(oct.version).toBe(2);
    expect(oct.tracks).toHaveLength(2);
    expect(oct.tracks[0]).toEqual({
      id: 'dQw4w9WgXcQ',
      title: 'Never Gonna Give You Up',
      artist: 'Rick Astley',
      album: 'Whenever You Need Somebody',
      albumId: 'MPREb_abc',
      artistId: 'UCuAXFkgsw1L7xaCfnd5JJOw',
      art: 'https://i.ytimg.com/vi/dQw4w9WgXcQ/hqdefault.jpg',
      ms: 2130000,
      plays: 10,
      last: 1729560000000,
      days: {
        '2026-10-03': { ms: 640000, plays: 3 },
        '2026-10-17': { ms: 1490000, plays: 7 },
      },
    });
    expect(oct.tracks[1]).toMatchObject({ id: 'content://media/external/audio/media/42', album: null, albumId: null, artistId: null, art: null });
    expect(oct.artists[0]).toMatchObject({ name: 'Rick Astley', sub: null, key: null, ms: 2130000, plays: 10 });
    expect(oct.artists[1]).toMatchObject({ name: 'Some Artist', id: null, key: null });
    expect(oct.albums[0]).toEqual({
      name: 'Whenever You Need Somebody',
      sub: 'Rick Astley',
      art: null,
      id: 'MPREb_abc',
      ms: 2130000,
      plays: 10,
      key: null,
      days: {
        '2026-10-03': { ms: 640000, plays: 3 },
        '2026-10-17': { ms: 1490000, plays: 7 },
      },
    });
    expect(oct.hours).toHaveLength(24);
    expect(oct.hours[18]).toBe(1200000);
    expect(oct.hours[19]).toBe(1230000);
    expect(oct.days).toEqual({ 3: 640000, 17: 1490000, 21: 300000 });
    expect(oct.trackDays).toEqual({
      '2026-10-03': { ms: 640000, plays: 3 },
      '2026-10-17': { ms: 1490000, plays: 7 },
      '2026-10-21': { ms: 300000, plays: 2 },
    });
    expect(oct.artistDays).not.toBeNull();
    expect(oct.albumDays).toEqual({
      '2026-10-03': { ms: 640000, plays: 3 },
      '2026-10-17': { ms: 1490000, plays: 7 },
    });

    const sep = file.listening[1]!;
    expect(sep).toEqual({
      version: 2,
      month: '2026-09',
      tracks: [],
      artists: [],
      albums: [],
      hours: new Array<number>(24).fill(0),
      days: {},
      trackDays: null,
      artistDays: null,
      albumDays: null,
    });
  });

  it('maps the four songs, nulls and all', () => {
    expect(library.tracks.map((t) => t.id)).toEqual([12, 19, 23, 31]);

    expect(library.tracks[0]).toEqual({
      id: 12,
      videoId: 'dQw4w9WgXcQ',
      spotifyId: null,
      isrc: null,
      localUri: null,
      path: null,
      title: 'Never Gonna Give You Up',
      artist: 'Rick Astley',
      artistId: 'UCuAXFkgsw1L7xaCfnd5JJOw',
      album: 'Whenever You Need Somebody',
      albumId: 'MPREb_abc',
      thumbnailUrl: 'https://i.ytimg.com/vi/dQw4w9WgXcQ/hqdefault.jpg',
      durationMs: 213000,
      durationText: '3:33',
      trackNumber: 1,
      discNumber: 1,
      albumArtist: 'Rick Astley',
      genre: null,
      year: 1987,
      folder: null,
      sizeBytes: null,
      modifiedAt: null,
      isVideo: false,
      origin: 'YTM',
      addedAt: 1730000001000,
      identStatus: null,
      identSource: null,
      suggestedVideoId: null,
      suggestedTitle: null,
      suggestedArtist: null,
      identifiedAt: null,
    });

    // A MediaStore file: a content:// uri and no videoId.
    expect(library.tracks[1]).toMatchObject({
      id: 19,
      videoId: null,
      localUri: 'content://media/external/audio/media/42',
      path: '/storage/emulated/0/Music/Some Artist/Song from file.mp3',
      folder: 'Music/Some Artist',
      sizeBytes: 4821033,
      modifiedAt: 1729990000000,
      origin: 'LOCAL',
      identStatus: 'REVIEW',
      identSource: 'FILE_NAME',
      suggestedVideoId: 'kJQP7kiw5Fk',
      suggestedTitle: 'Song from file (Official)',
      suggestedArtist: 'Some Artist',
      identifiedAt: 1730000003000,
    });

    // A download: both a videoId and a content:// uri.
    expect(library.tracks[2]).toMatchObject({
      id: 23,
      videoId: 'jNQXAC9IVRw',
      localUri: 'content://media/external/audio/media/57',
      origin: 'DOWNLOAD',
      identStatus: 'MATCHED',
      identSource: 'SEARCH',
      identifiedAt: 1730000600000,
    });

    // A Spotify-mapped song.
    expect(library.tracks[3]).toMatchObject({
      id: 31,
      videoId: '9bZkp7q19f0',
      spotifyId: '4uLU6hMCjMI75M1A2tKUQC',
      isrc: 'KRA201200001',
      isVideo: true,
      origin: 'SPOTIFY',
      year: 2012,
    });
  });

  it('maps the two playlists', () => {
    expect(library.playlists[0]).toEqual({
      id: 'sp_local_1730000000000',
      title: 'Late night',
      createdAt: 1730000000000,
      updatedAt: 1730000900000,
      pinned: true,
      sourceId: null,
      coverUrl: null,
    });
    expect(library.playlists[1]).toEqual({
      id: 'sp_local_1730000600000',
      title: 'Spotify Mix',
      createdAt: 1730000600000,
      updatedAt: 1730000600000,
      pinned: false,
      sourceId: 'spotify:playlist:abc',
      coverUrl: 'http://i.scdn.co/image/ab67616d0000b273abc123',
    });
  });

  it('maps the five entries without inventing a fromSpotifyId', () => {
    expect(library.entries).toEqual([
      { entryId: 41, playlistId: 'sp_local_1730000000000', trackId: 12, position: 0 },
      { entryId: 42, playlistId: 'sp_local_1730000000000', trackId: 19, position: 1 },
      { entryId: 47, playlistId: 'sp_local_1730000000000', trackId: 23, position: 2 },
      { entryId: 48, playlistId: 'sp_local_1730000600000', trackId: 31, position: 0 },
      { entryId: 53, playlistId: 'sp_local_1730000600000', trackId: 12, position: 1 },
    ]);
    expect(library.entries.every((e) => !('fromSpotifyId' in e))).toBe(true);
  });

  it('maps the three lyrics variants, Android-only providers included', () => {
    expect(library.lyricsVariants).toEqual([
      {
        songKey: 'dQw4w9WgXcQ',
        provider: 'LRCLIB',
        format: 'LRC',
        content: '[00:18.20]Never gonna give you up\n[00:22.00]Never gonna let you down',
        fetchedAt: 1730000100000,
      },
      {
        songKey: 'dQw4w9WgXcQ',
        provider: 'BETTER_LYRICS',
        format: 'TTML',
        content: '<tt xmlns="http://www.w3.org/ns/ttml"><body><div><p begin="00:00:18.200">Never gonna give you up</p></div></body></tt>',
        fetchedAt: 1730000200000,
      },
      {
        songKey: 'content://media/external/audio/media/42',
        provider: 'SIMP_MUSIC',
        format: 'ENHANCED_LRC',
        content: '[00:01.00]<00:01.00>Some <00:01.50>words',
        fetchedAt: 1730000300000,
      },
    ]);
  });

  it('maps the two lyrics choices, with a null provider and a negative offset', () => {
    expect(library.lyricsPrefs).toEqual([
      { songKey: 'dQw4w9WgXcQ', activeProvider: 'LRCLIB', offsetMs: -250 },
      { songKey: 'content://media/external/audio/media/42', activeProvider: null, offsetMs: 120 },
    ]);
  });

  it('maps the two Spotify matches, the songJson kept as the text Android wrote', () => {
    expect(library.spotifyMatches).toEqual([
      {
        spotifyId: '4uLU6hMCjMI75M1A2tKUQC',
        status: 'AUTO',
        songJson: '{"videoId":"9bZkp7q19f0","title":"Gangnam Style","artist":"PSY","durationText":"3:39","albumName":"PSY 6th Part 1","isVideo":true}',
        matchedAt: 1730000700000,
      },
      { spotifyId: '0VjIjW4GlUZAMYd2vXMi3b', status: 'MISSING', songJson: null, matchedAt: 1730000800000 },
    ]);
    expect(JSON.parse(library.spotifyMatches[0]!.songJson!)).toMatchObject({ videoId: '9bZkp7q19f0', isVideo: true });
  });
});

describe('parseBackup on the other files', () => {
  it('reads a BitChord file and leaves the library null', () => {
    const file = parseBackup(JSON.stringify(bitchordV1), NOW);
    expect(file.app).toBe('bitchord');
    expect(file.version).toBe(1);
    expect(file.library).toBeNull();
    expect(file.settings).toEqual(bitchordV1.settings);
    expect(file.listening).toHaveLength(1);
    expect(file.listening[0]!.hours[9]).toBe(540000);
  });

  it('keeps origin and settingsCapturedAt from a web-born file', () => {
    const file = parseBackup(JSON.stringify(webBorn), NOW);
    expect(file.app).toBe('auden');
    expect(file.version).toBe(2);
    expect(file.origin).toBe('web');
    expect(file.settingsCapturedAt).toBe('2026-11-01T08:00:00.000Z');
    expect(file.exportedAt).toBe('2026-11-05T21:40:02.118Z');
    expect(file.settings).toEqual({});
    expect(file.listening[0]!.hours[21]).toBe(213000);
    expect(file.listening[0]!.days).toEqual({ 5: 213000 });
    expect(file.library!.tracks.map((t) => t.localUri)).toEqual([null, 'auden-file://root-1/Music/Some Artist/Song from file.mp3']);
    expect(file.library!.entries.map((e) => e.trackId)).toEqual([1, 2]);
  });
});

describe('parseBackup refuses what the phone would refuse', () => {
  it('refuses text that is not JSON', () => {
    expect(refusalOf('{not json')).toBe(NOT_A_BACKUP);
  });

  it('refuses a JSON array', () => {
    expect(refusalOf('[{"app":"auden","version":2}]')).toBe(NOT_A_BACKUP);
  });

  it('refuses a missing app', () => {
    expect(refusalOf(JSON.stringify({ version: 2, settings: {} }))).toBe(NOT_A_BACKUP);
  });

  it('refuses another app', () => {
    expect(refusalOf(JSON.stringify({ app: 'spotify', version: 2 }))).toBe('That backup is from another app.');
  });

  it('refuses a missing version', () => {
    expect(refusalOf(JSON.stringify({ app: 'auden' }))).toBe(NOT_A_BACKUP);
  });

  it('refuses a newer version', () => {
    expect(refusalOf(JSON.stringify({ app: 'auden', version: 3 }))).toBe('That backup was written by a newer version of Auden.');
  });

  it('refuses version 0', () => {
    expect(refusalOf(JSON.stringify({ app: 'auden', version: 0 }))).toBe(NOT_A_BACKUP);
  });

  it('refuses a fractional version', () => {
    expect(refusalOf(JSON.stringify({ app: 'auden', version: 1.5 }))).toBe(NOT_A_BACKUP);
  });
});

describe('parseBackup refuses a row of the wrong shape', () => {
  const damaged = (what: string) => `That backup is damaged: ${what}.`;

  it('refuses a song without an id', () => {
    expect(refusalOf(withTracks(track({ id: undefined })))).toBe(damaged('a song has no id'));
  });

  it('refuses a song with id 0', () => {
    expect(refusalOf(withTracks(track({ id: 0 })))).toBe(damaged('a song has a field "id" that is not a usable id'));
  });

  it('refuses a song with a fractional id', () => {
    expect(refusalOf(withTracks(track({ id: 1.5 })))).toBe(damaged('a song has a field "id" that is not a usable id'));
  });

  it('refuses a song with an id past 2^53', () => {
    expect(refusalOf(withTracks(track({ id: 2 ** 53 })))).toBe(damaged('a song has a field "id" that is not a usable id'));
  });

  it('refuses a song without a title', () => {
    expect(refusalOf(withTracks(track({ title: undefined })))).toBe(damaged('a song has no title'));
  });

  it('refuses a song whose isVideo is text', () => {
    expect(refusalOf(withTracks(track({ isVideo: 'yes' })))).toBe(damaged('a song has a field "isVideo" that is not true or false'));
  });

  it('refuses a playlist entry with trackId 0', () => {
    const text = libraryWith({
      tracks: [track()],
      playlists: [{ id: 'sp_local_1730000000000', title: 'Mix', createdAt: 1730000000000 }],
      entries: [{ entryId: 1, playlistId: 'sp_local_1730000000000', trackId: 0, position: 0 }],
    });
    expect(refusalOf(text)).toBe(damaged('a playlist entry has a field "trackId" that is not a usable id'));
  });

  it('refuses a month that is not YYYY-MM', () => {
    expect(refusalOf(withListening(bucket({ month: 'nov-2026' })))).toBe(damaged('a month of listening has a month that is not YYYY-MM (nov-2026)'));
  });

  it('refuses days that are text rather than an object', () => {
    expect(refusalOf(withListening(bucket({ days: 'x' })))).toBe(damaged('a month of listening has days that are not an object'));
  });

  it('refuses a day whose value is a string', () => {
    expect(refusalOf(withListening(bucket({ days: { '5': '3' } })))).toBe(damaged('a month of listening has a day that is not a number'));
  });
});

describe('parseBackup accepts what the regex and the defaults allow', () => {
  it('accepts a month of 2026-13, since only the shape is checked', () => {
    const file = parseBackup(withListening(bucket({ month: '2026-13' })), NOW);
    expect(file.listening[0]!.month).toBe('2026-13');
  });

  it('fills what Kotlin defaults would, from the given now', () => {
    const file = parseBackup(
      libraryWith({
        tracks: [track({ id: 5 })],
        playlists: [{ id: 'sp_local_1730000000000', title: 'Mix', createdAt: 1730000000000 }],
        entries: [{ entryId: 9, playlistId: 'sp_local_1730000000000', trackId: 5, position: 0 }],
        lyricsVariants: [{ songKey: 'abc', provider: 'LRCLIB', format: 'LRC', content: '[00:01.00]hi' }],
      }),
      NOW,
    );
    const lib = file.library!;
    expect(lib.tracks[0]).toMatchObject({ id: 5, origin: 'YTM', addedAt: NOW, isVideo: false, videoId: null, localUri: null });
    expect(lib.playlists[0]).toMatchObject({ pinned: false, updatedAt: 1730000000000, createdAt: 1730000000000, sourceId: null });
    expect(lib.entries[0]).toEqual({ entryId: 9, playlistId: 'sp_local_1730000000000', trackId: 5, position: 0 });
    expect(lib.lyricsVariants[0]).toMatchObject({ fetchedAt: NOW });
  });

  it('pads hours shorter than 24 with zeros', () => {
    const file = parseBackup(withListening(bucket({ hours: [1, 2, 3] })), NOW);
    expect(file.listening[0]!.hours).toEqual([1, 2, 3, ...new Array<number>(21).fill(0)]);
  });

  it('ignores keys it does not know', () => {
    const text = JSON.stringify({
      app: 'auden',
      version: 2,
      futureFlag: true,
      library: { tracks: [track({ futureField: 'x' })] },
      listening: [bucket({ futureChart: [] })],
    });
    const file = parseBackup(text, NOW);
    expect(file).not.toHaveProperty('futureFlag');
    expect(file.library!.tracks[0]).not.toHaveProperty('futureField');
    expect(file.listening[0]).not.toHaveProperty('futureChart');
  });
});

describe('suggestedBackupName', () => {
  it('dates the file as Android names it', () => {
    expect(suggestedBackupName(new Date(2026, 10, 2))).toBe('auden-backup-2026-11-02.json');
  });

  it('pads a single-digit month and day', () => {
    expect(suggestedBackupName(new Date(2026, 0, 9))).toBe('auden-backup-2026-01-09.json');
  });
});
