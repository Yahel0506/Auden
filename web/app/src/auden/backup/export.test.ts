import { describe, expect, it } from 'vitest';
import type { StoredBucket, TrackRow } from '../db/schema';
import { buildBackup, phonePlaylistId, selfCheck, type Snapshot } from './export';
import { parseBackup } from './format';
import type { HeldSettings } from './passthrough';

const NOW = new Date('2026-11-06T12:00:00Z');
const track = (id: number, over: Partial<TrackRow> = {}): TrackRow & { id: number } => ({
  id,
  videoId: `vid${id}`,
  spotifyId: null,
  isrc: null,
  localUri: null,
  path: null,
  title: `Song ${id}`,
  artist: 'Artist',
  artistId: null,
  album: null,
  albumId: null,
  thumbnailUrl: null,
  durationMs: 200_000,
  durationText: null,
  trackNumber: null,
  discNumber: null,
  albumArtist: null,
  genre: null,
  year: null,
  folder: null,
  sizeBytes: null,
  modifiedAt: null,
  isVideo: false,
  origin: 'YTM',
  addedAt: 1_700_000_000_000,
  identStatus: null,
  identSource: null,
  suggestedVideoId: null,
  suggestedTitle: null,
  suggestedArtist: null,
  identifiedAt: null,
  ...over,
});
const playlist = (id: string, over = {}) => ({ id, title: id, createdAt: 1, updatedAt: 2, pinned: false, sourceId: null, coverUrl: null, ...over });
const entry = (entryId: number, playlistId: string, trackId: number, position: number) => ({ entryId, playlistId, trackId, position });
const empty = (): Snapshot => ({ tracks: [], playlists: [], entries: [], lyricsVariants: [], lyricsPrefs: [], spotifyMatches: [], listening: [] });
const build = (s: Partial<Snapshot>, held: HeldSettings | null = null) => buildBackup({ ...empty(), ...s }, held, { now: NOW, versionName: 'web test' });
const bucket = (over: Partial<StoredBucket> = {}): StoredBucket => ({ version: 1, month: '2026-11', tracks: [], artists: [], albums: [], hours: new Array<number>(24).fill(0), days: {}, trackDays: null, artistDays: null, albumDays: null, ...over });

describe('the header', () => {
  it('is Android\'s: app, version, a name for the reader, a time, and where it came from', () => {
    const { file } = build({});
    expect(file).toMatchObject({ app: 'auden', version: 2, versionName: 'web test', exportedAt: '2026-11-06T12:00:00.000Z', origin: 'web' });
  });

  it('carries the phone\'s settings back exactly as the last backup from it had them, and says how old', () => {
    const held: HeldSettings = { settings: { a: { type: 'bool', value: 'true' }, tags: { type: 'stringSet', values: ['x', 'y'] } }, capturedAt: '2026-10-03T08:00:00Z', versionName: '1.10.2' };
    const { file, report } = build({}, held);
    expect(file.settings).toEqual(held.settings);
    expect(file.settingsCapturedAt).toBe('2026-10-03T08:00:00Z');
    expect(report.phoneSettings).toEqual({ capturedAt: '2026-10-03T08:00:00Z', versionName: '1.10.2' });
  });

  it('has none to carry for a backup that was never on the phone, and says so', () => {
    const { file, report } = build({});
    expect(file.settings).toEqual({});
    expect(file.settingsCapturedAt).toBeUndefined();
    expect(report.phoneSettings).toBeNull();
  });
});

describe('songs', () => {
  it('leaves out a file that exists only in a browser folder, and the playlist entries that held it', () => {
    const { file, report } = build({
      tracks: [track(1), track(2, { videoId: null, localUri: 'auden-file://root1/a.mp3', path: 'a.mp3' })],
      playlists: [playlist('local:playlist:p')],
      entries: [entry(10, 'local:playlist:p', 1, 0), entry(11, 'local:playlist:p', 2, 1)],
    });
    expect(file.library!.tracks.map((t) => t.id)).toEqual([1]);
    expect(file.library!.entries).toHaveLength(1);
    expect(report).toMatchObject({ songs: 1, webFilesLeftOut: 1, entriesLeftOut: 1, entries: 1 });
  });

  it('sends a browser file that has a video id as a plain stream: no file fields the phone would act on', () => {
    const { file } = build({ tracks: [track(1, { localUri: 'auden-file://root1/a.mp3', path: 'a.mp3', folder: 'Music', sizeBytes: 5, modifiedAt: 9 })] });
    expect(file.library!.tracks[0]).toMatchObject({ videoId: 'vid1', localUri: null, path: null, folder: null, sizeBytes: null, modifiedAt: null });
  });

  it('gives a song the phone had a file for that file back exactly, and never writes the web-only stash', () => {
    const androidFile = { localUri: 'content://media/external/audio/media/42', path: '/storage/Music/a.mp3', folder: 'Music', sizeBytes: 4096, modifiedAt: 1_700_000_000_123 };
    const { file } = build({ tracks: [track(1, { videoId: null, androidFile })] });
    const out = file.library!.tracks[0]!;
    expect(out).toMatchObject(androidFile);
    expect('androidFile' in out).toBe(false);
  });

  it('writes whole numbers where Kotlin wants a Long or an Int, and no null where it wants a value', () => {
    const { file } = build({
      tracks: [track(1, { durationMs: 199_999.6, addedAt: 1.5e12 + 0.4, year: 1999.0, isVideo: undefined as never, origin: '' })],
    });
    const t = file.library!.tracks[0]!;
    expect(Number.isInteger(t.durationMs)).toBe(true);
    expect(Number.isInteger(t.addedAt)).toBe(true);
    expect(t.isVideo).toBe(false);
    expect(t.origin).toBe('YTM');
    expect(typeof t.title).toBe('string');
  });
});

describe('playlists', () => {
  it('uses the phone\'s ids (its lookups strip `local:playlist:`, so the web\'s would miss) and rewrites the entries to match', () => {
    expect(phonePlaylistId('local:playlist:abc')).toBe('sp_local_abc');
    expect(phonePlaylistId('sp_local_123')).toBe('sp_local_123');
    const { file } = build({ tracks: [track(1)], playlists: [playlist('local:playlist:abc'), playlist('sp_local_9')], entries: [entry(1, 'local:playlist:abc', 1, 0)] });
    expect(file.library!.playlists.map((p) => p.id)).toEqual(['sp_local_abc', 'sp_local_9']);
    expect(file.library!.entries[0]!.playlistId).toBe('sp_local_abc');
    expect(file.library!.playlists.some((p) => p.id.startsWith('local:playlist:'))).toBe(false);
  });

  it('does not carry a cover that lives only in this browser', () => {
    const { file } = build({ playlists: [playlist('a', { coverUrl: 'auden-art://playlist%3Aa%3A1' }), playlist('b', { coverUrl: 'https://i.ytimg.com/x.jpg' })] });
    expect(file.library!.playlists.map((p) => p.coverUrl)).toEqual([null, 'https://i.ytimg.com/x.jpg']);
  });

  it('renumbers positions densely from 0 in the old order, gives each entry a real id, and drops entries with nowhere to point', () => {
    const { file, report } = build({
      tracks: [track(1), track(2), track(3)],
      playlists: [playlist('a')],
      entries: [entry(50, 'a', 3, 9), entry(40, 'a', 1, 2), entry(60, 'a', 2, 4), entry(70, 'gone', 1, 0), entry(80, 'a', 99, 5)],
    });
    expect(file.library!.entries.map((e) => [e.trackId, e.position])).toEqual([[1, 0], [2, 1], [3, 2]]);
    expect(file.library!.entries.map((e) => e.entryId)).toEqual([1, 2, 3]);
    expect(report.entriesLeftOut).toBe(2);
  });

  it('keeps the web-only mark of which Spotify song put an entry there (the phone ignores unknown keys)', () => {
    const { file } = build({ tracks: [track(1)], playlists: [playlist('a')], entries: [{ ...entry(1, 'a', 1, 0), fromSpotifyId: 'sp9' }] });
    expect(file.library!.entries[0]!.fromSpotifyId).toBe('sp9');
  });
});

describe('lyrics', () => {
  const variant = (songKey: string, provider: string) => ({ songKey, provider, format: 'LRC', content: '[00:01.00]x', fetchedAt: 5.4 });

  it('leaves out lyrics keyed by a file in a browser folder, and a choice that names nothing stored', () => {
    const { file } = build({
      lyricsVariants: [variant('vid1', 'LRCLIB'), variant('auden-file://r/a.mp3', 'LRCLIB')],
      lyricsPrefs: [
        { songKey: 'vid1', activeProvider: 'LRCLIB', offsetMs: -250.2 },
        { songKey: 'vid2', activeProvider: 'KUGOU', offsetMs: 100 },
        { songKey: 'vid3', activeProvider: 'KUGOU', offsetMs: null },
        { songKey: 'auden-file://r/a.mp3', activeProvider: 'LRCLIB', offsetMs: 5 },
      ],
    });
    expect(file.library!.lyricsVariants.map((v) => v.songKey)).toEqual(['vid1']);
    expect(file.library!.lyricsVariants[0]!.fetchedAt).toBe(5);
    // vid2's choice is dropped (nothing behind it) but its offset stays; vid3 has nothing left; the file's goes.
    expect(file.library!.lyricsPrefs).toEqual([
      { songKey: 'vid1', activeProvider: 'LRCLIB', offsetMs: -250 },
      { songKey: 'vid2', activeProvider: null, offsetMs: 100 },
    ]);
  });
});

describe('listening', () => {
  it('writes whole numbers: a fractional millisecond would make the phone refuse the whole file', () => {
    const { file } = build({
      listening: [
        bucket({
          tracks: [{ id: 'abcdefghijk', title: 'T', artist: 'A', album: null, albumId: null, artistId: null, art: null, ms: 5000.3, plays: 1, last: 1_700_000_000_000.7, days: { '2026-11-05': { ms: 1234.6, plays: 1 } } }],
          artists: [{ name: 'A', sub: null, art: null, id: null, ms: 99.5, plays: 1, key: null, days: {} }],
          hours: [0.4, 1000.6],
          days: { 5: 1234.9 },
          trackDays: { '2026-11-05': { ms: 1.2, plays: 1 } },
        }),
      ],
    });
    const b = file.listening[0]!;
    expect(b.tracks[0]).toMatchObject({ ms: 5000, last: 1_700_000_000_001, days: { '2026-11-05': { ms: 1235, plays: 1 } } });
    expect(b.artists[0]!.ms).toBe(100);
    expect(b.hours).toHaveLength(24);
    expect(b.hours.slice(0, 2)).toEqual([0, 1001]);
    expect(b.days).toEqual({ 5: 1235 });
    expect(b.trackDays).toEqual({ '2026-11-05': { ms: 1, plays: 1 } });
    // Every number anywhere in it is whole.
    expect(JSON.stringify(file.listening)).not.toMatch(/\d\.\d/);
  });

  it('leaves out songs that are files in a browser folder, and artwork that lives only in this browser', () => {
    const { file } = build({
      listening: [
        bucket({
          tracks: [
            { id: 'auden:local:7', title: 'L', artist: 'A', album: null, albumId: null, artistId: null, art: null, ms: 1, plays: 0, last: 1, days: {} },
            { id: 'vid1', title: 'Y', artist: 'A', album: null, albumId: null, artistId: null, art: 'blob:http://localhost/xyz', ms: 1, plays: 0, last: 1, days: {} },
            { id: 'vid2', title: 'Z', artist: 'A', album: null, albumId: null, artistId: null, art: 'https://i.ytimg.com/a.jpg', ms: 1, plays: 0, last: 1, days: {} },
          ],
        }),
      ],
    });
    expect(file.listening[0]!.tracks.map((t) => [t.id, t.art])).toEqual([['vid1', null], ['vid2', 'https://i.ytimg.com/a.jpg']]);
  });

  it('is in month order whatever order the store gave', () => {
    const { file } = build({ listening: [bucket({ month: '2026-11' }), bucket({ month: '2026-09' }), bucket({ month: '2026-10' })] });
    expect(file.listening.map((b) => b.month)).toEqual(['2026-09', '2026-10', '2026-11']);
  });
});

describe('the whole file', () => {
  const snapshot = (): Snapshot => ({
    ...empty(),
    tracks: [track(1), track(2)],
    playlists: [playlist('local:playlist:abc')],
    entries: [entry(1, 'local:playlist:abc', 2, 0), entry(2, 'local:playlist:abc', 1, 1)],
    lyricsVariants: [{ songKey: 'vid1', provider: 'LRCLIB', format: 'LRC', content: '[00:01.00]x', fetchedAt: 1 }],
    spotifyMatches: [{ spotifyId: 's1', status: 'AUTO', songJson: '{"videoId":"vid1","title":"t","artist":"a","isVideo":false}', matchedAt: 7 }],
    listening: [bucket({ tracks: [{ id: 'vid1', title: 'T', artist: 'A', album: null, albumId: null, artistId: null, art: null, ms: 1000, plays: 1, last: 2, days: {} }] })],
  });

  it('reads back through the strict reader as the same library', () => {
    const { file } = build(snapshot(), { settings: { k: { type: 'string', value: 'v' } }, capturedAt: 'c', versionName: 'v' });
    const back = parseBackup(JSON.stringify(file), 1);
    expect(back.library!.tracks.map((t) => t.id)).toEqual([1, 2]);
    expect(back.library!.entries.map((e) => [e.playlistId, e.trackId, e.position])).toEqual([['sp_local_abc', 2, 0], ['sp_local_abc', 1, 1]]);
    expect(back.settings).toEqual({ k: { type: 'string', value: 'v', values: [] } });
    expect(back.origin).toBe('web');
    expect(back.listening).toHaveLength(1);
  });

  it('is the same file for the same data (and does not touch what it was given)', () => {
    const s = snapshot();
    const before = JSON.stringify(s);
    expect(JSON.stringify(build(s).file)).toBe(JSON.stringify(build(s).file));
    expect(JSON.stringify(s)).toBe(before);
  });

  it('every entry points at a song and a playlist that are in the file (the phone enforces it)', () => {
    const { file } = build({ ...snapshot(), tracks: [track(1), track(2, { videoId: null, localUri: 'auden-file://r/x.mp3' })] });
    const songs = new Set(file.library!.tracks.map((t) => t.id));
    const lists = new Set(file.library!.playlists.map((p) => p.id));
    for (const e of file.library!.entries) {
      expect(songs.has(e.trackId)).toBe(true);
      expect(lists.has(e.playlistId)).toBe(true);
      expect(e.entryId).toBeGreaterThanOrEqual(1);
    }
  });
});

describe('the check before the file is written', () => {
  const sound = (): Snapshot => ({ ...empty(), tracks: [track(1), track(2)], playlists: [playlist('a')], entries: [entry(1, 'a', 1, 0), entry(2, 'a', 2, 1)] });

  it('finds nothing wrong with a sound file', () => {
    expect(selfCheck(build(sound()).file)).toEqual([]);
  });

  it('refuses two songs that name the same file: the phone would silently replace the first and strand its entries', () => {
    const snap = sound();
    snap.tracks = [track(1, { localUri: 'content://media/1' }), track(2, { localUri: 'content://media/1' })];
    const file = build(snap).file;
    expect(selfCheck(file)).toContain('two songs name the same file');
  });

  it('refuses an entry that points at nothing, and a song with no usable id', () => {
    const file = build(sound()).file;
    file.library!.entries.push({ entryId: 9, playlistId: 'sp_local_gone', trackId: 1, position: 5 });
    file.library!.tracks[0]!.id = 0;
    expect(selfCheck(file)).toEqual(expect.arrayContaining(['a playlist entry points at nothing', 'a song has no usable id']));
  });

  it('keeps null out of the names Kotlin declares as text, and web-only artwork out of artists and albums', () => {
    const { file } = build({
      listening: [
        bucket({
          tracks: [{ id: 'v', title: null as never, artist: null as never, album: null, albumId: null, artistId: null, art: null, ms: 1, plays: 0, last: 1, days: {} }],
          artists: [{ name: null as never, sub: null, art: 'blob:http://localhost/x', id: null, ms: 1, plays: 0, key: null, days: {} }],
          albums: [{ name: 'Al', sub: null, art: 'auden-art://k', id: null, ms: 1, plays: 0, key: null, days: {} }],
        }),
      ],
    });
    const b = file.listening[0]!;
    expect(b.tracks[0]).toMatchObject({ title: '', artist: '' });
    expect(b.artists[0]).toMatchObject({ name: '', art: null });
    expect(b.albums[0]!.art).toBeNull();
  });
});
