import { describe, expect, it } from 'vitest';
import {
  buildLocalShelves,
  localDayNumber,
  LocalShelfKind,
  seededShuffle,
  textFor,
  type LibraryTrack,
  type LocalShelf,
  type LocalShelfText,
  type PlayedTrack,
  type PlaylistSummary,
} from './shelves';

const DAY = 24 * 60 * 60 * 1000;
// Local noon, so that an hour later is still the same local day and a day later is the next one.
const now = new Date(2026, 0, 15, 12).getTime();

const text: LocalShelfText = {
  title: (kind, artist) => (artist !== undefined ? `${kind}:${artist}` : kind),
  subtitle: () => '',
  songCount: (n) => `${n} songs`,
};

const played = (id: string, over: Partial<PlayedTrack> = {}): PlayedTrack => ({
  id,
  title: `t${id}`,
  artist: 'A',
  art: null,
  playsTotal: 1,
  plays30: 0,
  lastPlayedAt: now,
  localUri: null,
  ...over,
});

const lib = (id: string, artist = 'A', addedAt = 0, file = true): LibraryTrack => ({
  id,
  title: `t${id}`,
  artist,
  art: null,
  localUri: file ? `content://${id}` : null,
  addedAt,
});

const range = (from: number, to: number): number[] => Array.from({ length: to - from + 1 }, (_, i) => from + i);

const build = (
  played: PlayedTrack[],
  library: LibraryTrack[],
  playlists: PlaylistSummary[] = [],
  at: number = now,
): LocalShelf[] => buildLocalShelves(played, library, playlists, at, text);

function find(shelves: LocalShelf[], kind: LocalShelfKind): LocalShelf {
  const shelf = shelves.find((s) => s.kind === kind);
  if (!shelf) throw new Error(`no ${kind} shelf`);
  return shelf;
}

const ids = (shelf: LocalShelf): string[] => shelf.items.map((i) => i.id);

describe('local shelves', () => {
  it('a fresh install builds no song shelves', () => {
    expect(build([], [])).toEqual([]);
  });

  it('on repeat takes the most played of the month and needs four songs', () => {
    const tracks = range(1, 5).map((n) => played(`s${n}`, { plays30: 10 - n, playsTotal: 10 }));
    const shelf = find(build(tracks, []), LocalShelfKind.LOOP);
    expect(ids(shelf)).toEqual(['s1', 's2', 's3', 's4', 's5']);

    const few = range(1, 3).map((n) => played(`s${n}`, { plays30: 5 }));
    expect(build(few, []).some((s) => s.kind === LocalShelfKind.LOOP)).toBe(false);
  });

  it('a song heard once this month is not on repeat', () => {
    const tracks = range(1, 6).map((n) => played(`s${n}`, { plays30: 1 }));
    expect(build(tracks, []).some((s) => s.kind === LocalShelfKind.LOOP)).toBe(false);
  });

  it('rediscover is loved and long silent', () => {
    const old = now - 120 * DAY;
    const forgotten = range(1, 4).map((n) => played(`f${n}`, { playsTotal: 8, lastPlayedAt: old }));
    const recent = range(1, 4).map((n) => played(`r${n}`, { playsTotal: 8, lastPlayedAt: now - 5 * DAY }));
    const shelf = find(build([...forgotten, ...recent], []), LocalShelfKind.REDISCOVER);
    expect(new Set(ids(shelf))).toEqual(new Set(['f1', 'f2', 'f3', 'f4']));
  });

  it('recently added is newest first and only files', () => {
    const tracks = [...range(1, 6).map((n) => lib(`n${n}`, 'A', n)), lib('streamed', 'A', 99, false)];
    const shelf = find(build([], tracks), LocalShelfKind.RECENTLY_ADDED);
    expect(shelf.items[0]?.id).toBe('n6');
    expect(shelf.items.some((i) => i.id === 'streamed')).toBe(false);
  });

  it('playlists put pinned first and skip empty ones', () => {
    const playlists: PlaylistSummary[] = [
      { id: 'a', title: 'A', songCount: 3, art: null, pinned: false },
      { id: 'b', title: 'B', songCount: 0, art: null, pinned: true },
      { id: 'c', title: 'C', songCount: 2, art: null, pinned: true },
    ];
    const shelf = find(build([], [], playlists), LocalShelfKind.PLAYLISTS);
    expect(shelf.items.map((i) => i.title)).toEqual(['C', 'A']);
    expect(shelf.items[0]).toMatchObject({ id: 'c', kind: 'playlist', subtitle: '2 songs' });
  });

  it('the artist mix is the artist heard most lately and joins library and history', () => {
    const history = [
      ...range(1, 2).map((n) => played(`h${n}`, { artist: 'Ana', plays30: 5, playsTotal: 5 })),
      played('x1', { artist: 'Bo', plays30: 1, playsTotal: 1 }),
    ];
    const library = range(1, 4).map((n) => lib(`l${n}`, 'Ana & Friend'));
    const shelf = find(build(history, library), LocalShelfKind.ARTIST_MIX);
    expect(shelf.title).toBe('ARTIST_MIX:Ana');
    expect(shelf.items.length).toBe(6);
  });

  it('the same day gives the same shuffle', () => {
    const library = range(1, 40).map((n) => lib(`l${n}`));
    const a = find(build([], library), LocalShelfKind.LIBRARY_SHUFFLE);
    const b = find(build([], library, [], now + 3_600_000), LocalShelfKind.LIBRARY_SHUFFLE);
    const tomorrow = find(build([], library, [], now + DAY), LocalShelfKind.LIBRARY_SHUFFLE);
    expect(a.items).toEqual(b.items);
    expect(a.items).not.toEqual(tomorrow.items);
  });

  it('the day comes from the injected dayOf, so a new injected day reshuffles and the same day does not', () => {
    const library = range(1, 40).map((n) => lib(`l${n}`));
    const utcDay = (ms: number) => Math.floor(ms / DAY);
    const anchor = 1000 * DAY;
    const shuffleAt = (at: number, dayOf: (ms: number) => number) =>
      find(buildLocalShelves([], library, [], at, text, dayOf), LocalShelfKind.LIBRARY_SHUFFLE);
    const first = shuffleAt(anchor, utcDay);
    expect(ids(shuffleAt(anchor + 3_600_000, utcDay))).toEqual(ids(first));
    expect(ids(shuffleAt(anchor + DAY, utcDay))).not.toEqual(ids(first));
    // A constant day ignores the clock entirely.
    expect(ids(shuffleAt(anchor + DAY, () => 7))).toEqual(ids(shuffleAt(anchor, () => 7)));
  });

  it('a local file keeps its uri on the card', () => {
    const tracks = range(1, 4).map((n) => played(`content://m/${n}`, { plays30: 3, playsTotal: 3 }));
    const shelf = find(build(tracks, []), LocalShelfKind.LOOP);
    expect(shelf.items[0]?.localUri).toBe('content://m/1');
  });

  it('the default day is the local calendar day', () => {
    expect(localDayNumber(now)).toBe(localDayNumber(now + 3_600_000));
    expect(localDayNumber(now + DAY)).toBe(localDayNumber(now) + 1);
  });

  it('seededShuffle keeps every item and depends only on the seed', () => {
    const items = range(1, 10);
    const once = seededShuffle(items, 5);
    expect([...once].sort((x, y) => x - y)).toEqual(items);
    expect(seededShuffle(items, 5)).toEqual(once);
    expect(items).toEqual(range(1, 10));
  });

  it('textFor gives the listener wording in English and Spanish', () => {
    const en = textFor('en');
    const es = textFor('es');
    expect(en.title(LocalShelfKind.ARTIST_MIX, 'Ana')).toBe('Mix: Ana');
    expect(en.songCount(1)).toBe('1 song');
    expect(en.songCount(3)).toBe('3 songs');
    expect(es.title(LocalShelfKind.LOOP)).toBe('En bucle');
    expect(es.songCount(1)).toBe('1 canción');
    expect(es.songCount(3)).toBe('3 canciones');
  });
});
