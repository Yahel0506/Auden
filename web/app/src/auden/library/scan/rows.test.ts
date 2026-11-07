import { describe, expect, it } from 'vitest';
import { newTrack } from '../../db/dao';
import type { ScanEntry } from './protocol';
import { formatDuration, idFromEntry, rowFromEntry, titleFromFileName } from './rows';

const entry = (over: Partial<ScanEntry> = {}): ScanEntry => ({
  relPath: 'Rock/Hits/01 Song.mp3',
  size: 1000,
  modifiedAt: 5,
  artKey: 'album:a|b',
  tags: { title: 'Song', artist: 'A & B', album: 'Hits', albumArtist: 'A', genre: 'Rock', year: 1999, trackNumber: 1, discNumber: 1, durationSec: 215.4 },
  ...over,
});

describe('rowFromEntry', () => {
  it('maps tags to the Room-named fields with an auden-file uri', () => {
    const row = rowFromEntry('r1', entry());
    expect(row).toMatchObject({
      localUri: 'auden-file://r1/Rock/Hits/01%20Song.mp3',
      path: 'Rock/Hits/01 Song.mp3',
      folder: 'Rock/Hits',
      title: 'Song',
      artist: 'A & B',
      genre: 'Rock',
      durationMs: 215400,
      durationText: '3:35',
      origin: 'LOCAL',
      videoId: null,
    });
  });
  it('falls back to the file name and Unknown Artist when tags are unreadable', () => {
    const row = rowFromEntry('r1', entry({ tags: null, relPath: 'x/Artist - Title [dQw4w9WgXcQ].m4a' }));
    expect(row.title).toBe('Artist - Title');
    expect(row.artist).toBe('Unknown Artist');
    expect(idFromEntry(entry({ relPath: 'x/Artist - Title [dQw4w9WgXcQ].m4a' }))).toBe('dQw4w9WgXcQ');
  });
  it('keeps the id, when it was added, the YouTube id and the identification of a changed file', () => {
    const existing = newTrack({
      id: 7, title: 'Old', artist: 'Old', addedAt: 111, videoId: 'dQw4w9WgXcQ', identStatus: 'MATCHED', identSource: 'SEARCH', identifiedAt: 9, origin: 'DOWNLOAD',
    });
    const row = rowFromEntry('r1', entry(), existing);
    expect(row).toMatchObject({ id: 7, addedAt: 111, videoId: 'dQw4w9WgXcQ', identStatus: 'MATCHED', identSource: 'SEARCH', identifiedAt: 9, origin: 'DOWNLOAD', title: 'Song' });
  });
  it('formats durations and names', () => {
    expect(formatDuration(3725)).toBe('1:02:05');
    expect(titleFromFileName('a/b/Name [dQw4w9WgXcQ].opus')).toBe('Name');
  });
});
