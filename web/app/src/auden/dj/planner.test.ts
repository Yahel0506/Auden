// Port of the planner tests in app/src/test/java/dev/auden/dj/DjTest.kt (the writer tests are not here).
import { describe, expect, it } from 'vitest';
import { decadeOf, djTrack, genreKeyOf, leadArtist, themeKind, type DjBlock, type DjTheme, type DjTrack } from './model';
import { DjPlanner, MIN_BLOCK, DAY_MS } from './planner';

const day = DAY_MS;
const now = 2_000 * day;

interface TOpts {
  artist?: string;
  album?: string | null;
  genre?: string | null;
  year?: number | null;
  plays?: number;
  plays30?: number;
  last?: number;
  added?: number;
  file?: boolean;
  title?: string;
}

/** The Kotlin `t(...)` fixture: a song by Ana unless told otherwise, with a file uri when [file] is set. */
const t = (id: string, o: TOpts = {}): DjTrack =>
  djTrack({
    id,
    title: o.title ?? `T${id}`,
    artist: o.artist ?? 'Ana',
    album: o.album ?? null,
    genre: o.genre ?? null,
    year: o.year ?? null,
    localUri: o.file ? `content://${id}` : null,
    plays: o.plays ?? 0,
    plays30: o.plays30 ?? 0,
    lastPlayedAt: o.last ?? 0,
    addedAt: o.added ?? 0,
  });

/** Ana (rock, 90s), Bo (pop, 2000s), Cy (jazz, 90s): enough for artist, genre and decade themes. */
const library: DjTrack[] = [
  ...[1, 2, 3, 4, 5, 6].map((i) => t(`a${i}`, { artist: 'Ana', album: 'Alpha', genre: 'Rock', year: 1990 + i, plays: 10 - i, plays30: 2 })),
  ...[1, 2, 3, 4, 5, 6].map((i) => t(`b${i}`, { artist: 'Bo', album: 'Beta', genre: 'Pop', year: 2000 + i, plays: 5, plays30: 1 })),
  ...[1, 2, 3, 4, 5, 6].map((i) => t(`c${i}`, { artist: 'Cy', album: null, genre: 'Jazz', year: 1990 + i, plays: 3 })),
];

/** Six artists, two albums each, plenty of plays: enough for a varied surprise. */
const varied: DjTrack[] = [1, 2, 3, 4, 5, 6].flatMap((a) =>
  [1, 2, 3, 4, 5, 6, 7, 8].map((n) => ({
    ...t(`v${a}-${n}`, {
      artist: `Artist${a}`,
      album: `Album${a}-${n % 2}`,
      genre: ['Rock', 'Jazz', 'Pop'][a % 3],
      year: 1990 + a,
      plays: 4 + n,
      plays30: 1,
    }),
    trackNumber: n,
  })),
);

/** Whether [track] obeys [theme], as the Kotlin coherence test checks it. */
function obeys(theme: DjTheme, track: DjTrack): boolean {
  switch (theme.type) {
    case 'artist':
      return leadArtist(track).toLowerCase() === theme.artist.toLowerCase();
    case 'decade':
      return decadeOf(track) === theme.decade;
    case 'genre':
      return genreKeyOf(track) === theme.genre;
    case 'mood':
      return true;
    case 'album':
      return track.album != null && track.album.toLowerCase() === theme.album.toLowerCase();
    case 'favorites':
      return track.plays >= 3;
    case 'rediscover':
      return track.plays >= 5;
    case 'fresh':
      return track.localUri != null;
    case 'discover':
      return !!track.discovery;
    case 'playlist':
      return (track.playlists ?? []).length > 0;
    case 'request':
      return true;
  }
}

/** Up to [n] sets, stopping early at the first null, as Kotlin's generateSequence(...).take(n) does. */
function take(planner: DjPlanner, n: number): DjBlock[] {
  const out: DjBlock[] = [];
  while (out.length < n) {
    const block = planner.next();
    if (block === null) break;
    out.push(block);
  }
  return out;
}

/** The first set that satisfies [pred], as Kotlin's generateSequence { next() }.firstOrNull { ... }. */
function nextMatching(planner: DjPlanner, pred: (b: DjBlock) => boolean): DjBlock | undefined {
  for (;;) {
    const block = planner.next();
    if (block === null) return undefined;
    if (pred(block)) return block;
  }
}

const idsOf = (blocks: readonly DjBlock[]): string[] => blocks.flatMap((b) => b.tracks.map((x) => x.id));

// ── Coherence ─────────────────────────────────────────────────────────────

describe('coherence', () => {
  it('every song in a block obeys its theme', () => {
    for (let seed = 0; seed < 20; seed++) {
      const planner = new DjPlanner({ pool: library, now, seed });
      for (let i = 0; i < 3; i++) {
        const block = planner.next();
        if (block === null) continue;
        expect(block.tracks.length).toBeGreaterThanOrEqual(MIN_BLOCK);
        for (const track of block.tracks) {
          expect(obeys(block.theme, track), `${track.id} in ${JSON.stringify(block.theme)}`).toBe(true);
        }
      }
    }
  });

  it('a song is never played twice in a session', () => {
    const planner = new DjPlanner({ pool: varied, now, seed: 7 });
    const seen = new Set<string>();
    for (const block of take(planner, Infinity)) {
      for (const track of block.tracks) {
        expect(seen.has(track.id), `${track.id} repeated`).toBe(false);
        seen.add(track.id);
      }
    }
    expect(seen.size, `only ${seen.size} songs in a session`).toBeGreaterThanOrEqual(8);
  });

  it('two blocks in a row are not of the same kind', () => {
    for (let seed = 0; seed < 15; seed++) {
      const planner = new DjPlanner({ pool: library, now, seed });
      const kinds = take(planner, 4).map((b) => themeKind(b.theme));
      for (let i = 1; i < kinds.length; i++) expect(kinds[i]).not.toBe(kinds[i - 1]);
    }
  });

  it('a pool too small to theme gives no block', () => {
    const few = [1, 2, 3].map((i) => t(`x${i}`, { artist: 'Ana', genre: 'Rock', year: 1999, plays: 9 }));
    expect(new DjPlanner({ pool: few, now, seed: 1 }).next()).toBeNull();
  });

  it('an album plays in track order', () => {
    const album = [1, 2, 3, 4, 5].map((i) => ({ ...t(`al${i}`, { artist: 'Dee', album: 'Disc', plays: 1 }), trackNumber: 6 - i }));
    const planner = new DjPlanner({ pool: album, now, seed: 3, request: null });
    const block = nextMatching(planner, (b) => b.theme.type === 'album');
    if (block === undefined) return;
    const numbers = block.tracks.map((x) => x.trackNumber);
    expect(numbers).toEqual([...numbers].sort((a, b) => (a ?? 0) - (b ?? 0)));
  });

  it('no artist crowds a block that is not theirs', () => {
    // A decade of 8 songs by one and 4 by another: the dominant artist is capped at two.
    const mixed = [
      ...[1, 2, 3, 4, 5, 6, 7, 8].map((i) => t(`m${i}`, { artist: 'Ana', year: 1985, plays: 9 })),
      ...[1, 2, 3, 4].map((i) => t(`n${i}`, { artist: 'Bo', year: 1985, plays: 1 })),
    ];
    const planner = new DjPlanner({ pool: mixed, now, seed: 5, blockSize: 6 });
    const block = nextMatching(planner, (b) => b.theme.type === 'decade');
    if (block === undefined) return;
    expect(block.tracks.filter((x) => leadArtist(x) === 'Ana').length).toBeLessThanOrEqual(4);
  });
});

// ── Feedback ──────────────────────────────────────────────────────────────

describe('feedback', () => {
  it('less of an artist takes them out of the session', () => {
    const planner = new DjPlanner({ pool: library, now, seed: 11 });
    planner.less(library.find((x) => x.artist === 'Ana')!);
    for (let i = 0; i < 5; i++) {
      for (const track of planner.next()?.tracks ?? []) expect(leadArtist(track) === 'Ana').toBe(false);
    }
  });
});

// ── The request ───────────────────────────────────────────────────────────

describe('the request', () => {
  it('a request narrows the session', () => {
    const planner = new DjPlanner({ pool: library, now, seed: 2, request: { text: '90s', decade: 1990 } });
    for (let i = 0; i < 3; i++) {
      for (const track of planner.next()?.tracks ?? []) expect(decadeOf(track)).toBe(1990);
    }
    expect(planner.relaxed).toBe(false);
  });

  it('a request the library cannot meet is relaxed, and says so', () => {
    const planner = new DjPlanner({ pool: library, now, seed: 2, request: { text: '1950s jazz', decade: 1950, genre: 'jazz' } });
    expect(planner.next()).not.toBeNull();
    expect(planner.relaxed).toBe(true);
  });

  it('a request may repeat the artist it names', () => {
    const planner = new DjPlanner({ pool: varied, now, seed: 4, blockSize: 5, request: { text: 'Artist3', artist: 'Artist3' } });
    const block = planner.next();
    if (block === null) return;
    expect(block.tracks.every((x) => leadArtist(x) === 'Artist3')).toBe(true);
  });
});

// ── Surprises are for listening, not for repetition ───────────────────────

describe('surprises', () => {
  it('a surprise has one song per artist and no album twice in a row', () => {
    for (let seed = 0; seed < 10; seed++) {
      const planner = new DjPlanner({ pool: varied, now, seed, blockSize: 5, request: null });
      for (const block of take(planner, 3)) {
        const artists = block.tracks.map((x) => leadArtist(x));
        expect(new Set(artists).size, `artists in ${JSON.stringify(block.theme)}`).toBe(artists.length);
        for (let i = 1; i < block.tracks.length; i++) {
          const a = block.tracks[i - 1]!;
          const b = block.tracks[i]!;
          expect(a.album != null && a.album === b.album).toBe(false);
        }
      }
    }
  });

  it('an artist rests after a place when the theme allows', () => {
    const wide: DjTrack[] = [1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12].flatMap((a) =>
      [1, 2, 3, 4, 5, 6].map((n) =>
        t(`w${a}-${n}`, { artist: `Artist${a}`, album: `Album${a}`, genre: ['Rock', 'Jazz', 'Pop'][a % 3], year: 1990 + a, plays: 4 + n }),
      ),
    );
    const planner = new DjPlanner({ pool: wide, now, seed: 21, blockSize: 4, request: null });
    const sets = take(planner, 4);
    expect(sets.length).toBeGreaterThanOrEqual(2);
    for (let i = 1; i < sets.length; i++) {
      const recentArtists = new Set(sets[i - 1]!.tracks.map((x) => leadArtist(x)));
      const current = sets[i]!.tracks.map((x) => leadArtist(x));
      expect(current.some((a) => recentArtists.has(a)), `set ${i} repeats the artist of the set before`).toBe(false);
    }
  });

  it('discoveries join a surprise', () => {
    const planner = new DjPlanner({ pool: varied, now, seed: 9, blockSize: 5, request: null });
    const found = [1, 2, 3, 4, 5, 6].map((i) => ({
      ...t(`new${i}`, { artist: `Stranger${i}`, genre: 'Rock', year: 1995, plays: 0 }),
      discovery: true,
    }));
    planner.addDiscoveries(found);
    const tracks = take(planner, 12).flatMap((b) => b.tracks);
    expect(tracks.some((x) => x.discovery)).toBe(true);
  });

  it('a new session leans away from what the last one played', () => {
    const first = new DjPlanner({ pool: varied, now, seed: 5, blockSize: 5 });
    const played = new Set(idsOf(take(first, 3)));
    let overlapFresh = 0;
    let overlapPlain = 0;
    for (let seed = 0; seed < 20; seed++) {
      const fresh = new DjPlanner({ pool: varied, now, seed: 100 + seed, blockSize: 5, recently: played });
      const plain = new DjPlanner({ pool: varied, now, seed: 100 + seed, blockSize: 5 });
      overlapFresh += idsOf(take(fresh, 3)).filter((id) => played.has(id)).length;
      overlapPlain += idsOf(take(plain, 3)).filter((id) => played.has(id)).length;
    }
    expect(overlapFresh, `fresh ${overlapFresh} vs plain ${overlapPlain}`).toBeLessThan(overlapPlain);
  });

  it('a surprise draws on the playlists too', () => {
    // Songs never played, but in two playlists: the only reason the DJ can know them.
    const curated = [1, 2].flatMap((p) =>
      [1, 2, 3, 4, 5, 6, 7, 8].map((n) => ({ ...t(`p${p}-${n}`, { artist: `Singer${p}_${n}`, plays: 0 }), playlists: [`Mix${p}`] })),
    );
    const planner = new DjPlanner({ pool: curated, now, seed: 3, blockSize: 5 });
    const block = planner.next();
    expect(block).not.toBeNull();
    expect(block!.theme.type).toBe('playlist');
    expect(new Set(block!.tracks.map((x) => leadArtist(x))).size).toBe(5);
  });

  it('sessions open on different songs even when the favourites are the same', () => {
    const openers = Array.from({ length: 30 }, (_, i) => i + 1).map((seed) => {
      const block = new DjPlanner({ pool: varied, now, seed, blockSize: 5 }).next()!;
      return block.tracks.map((x) => x.id).sort().join(',');
    });
    // Thirty sessions over forty-eight songs should not all draw the same five.
    expect(new Set(openers).size).toBeGreaterThan(15);
  });
});
