// Port of dev/auden/dj/DjTaste.kt: how a typed ask is turned into searches that fit what the listener plays, and how
// the answers are mixed.
//
// "Música triste" alone finds the saddest songs of anything. The listener who plays rock and hip-hop wants the sad
// ones of those, unless they named another genre, so the ask is searched once in each of their genres. And an
// artist of theirs who has a song that fits is welcome whatever their genre, so it is searched from each of their
// favourite artists too. Pure: the session does the searching.

import { genreKeyOf, leadArtist, leadOf, normalizeGenre, type DjTrack } from './model';
import { fold } from './parser';

/** What the listener plays: their favourite artists, and the genres those artists and files are filed under. */
export class Profile {
  readonly artists: string[];
  readonly genres: string[];

  constructor(artists: string[], genres: string[]) {
    this.artists = artists;
    this.genres = genres;
  }

  get isEmpty(): boolean {
    return this.artists.length === 0 && this.genres.length === 0;
  }
}

/** The ask, as one search. Plain: the ask on its own. InGenre: the ask in one of the listener's genres. FromArtist: the ask from one of the listener's artists. */
export type Query =
  | { type: 'plain'; text: string }
  | { type: 'inGenre'; text: string; genre: string }
  | { type: 'fromArtist'; text: string; artist: string };

/** A song a search returned. */
export interface Hit {
  id: string;
  artist: string;
}

export const MAX_ARTISTS = 8;
export const MAX_GENRES = 3;
export const ARTISTS_SEARCHED = 5;
export const ARTIST_HITS = 2;
export const ARTIST_EXTRAS = 6;
export const PER_ARTIST = 2;

/** Tags that are about the listener, not the music. */
export const NOT_GENRES: ReadonlySet<string> = new Set(['seen live', 'favorites', 'favourites', 'male vocalists', 'female vocalists', 'spotify', 'all']);

/** The artists played most (this month counts double) and the genres they come in, from the songs themselves and from [genresOf]. */
export function profile(
  tracks: readonly DjTrack[],
  genresOf: (artist: string) => readonly string[],
  maxArtists: number = MAX_ARTISTS,
  maxGenres: number = MAX_GENRES,
): Profile {
  const own = tracks.filter((t) => !t.discovery && t.plays > 0);
  const byArtist = new Map<string, DjTrack[]>();
  for (const t of own) {
    const key = leadArtist(t);
    const list = byArtist.get(key);
    if (list) list.push(t);
    else byArtist.set(key, [t]);
  }
  const ranked = [...byArtist.entries()]
    .map(([artist, songs]) => ({ artist, score: songs.reduce((sum, t) => sum + t.plays30 * 2 + t.plays, 0) }))
    .sort((a, b) => b.score - a.score);
  const artists = ranked.slice(0, maxArtists).map((x) => x.artist);

  const weight = new Map<string, number>();
  const add = (genre: string, n: number) => weight.set(genre, (weight.get(genre) ?? 0) + n);
  for (const t of own) {
    const genre = genreKeyOf(t);
    if (genre != null) add(genre, t.plays);
  }
  artists.forEach((artist, i) => {
    for (const g of genresOf(artist).slice(0, 2)) {
      const genre = normalizeGenre(g);
      if (genre != null) add(genre, maxArtists - i);
    }
  });
  const genres = [...weight.entries()]
    .sort((a, b) => b[1] - a[1])
    .map(([genre]) => genre)
    .filter((genre) => !NOT_GENRES.has(genre))
    .slice(0, maxGenres);
  return new Profile(artists, genres);
}

/**
 * The searches for [text]. One that names a genre of its own ([explicitGenre]) is searched as it is: the listener
 * said what they want. Otherwise in each of their genres, and from each of their artists.
 */
export function queries(text: string, profile: Profile, explicitGenre: boolean): Query[] {
  if (explicitGenre || profile.isEmpty) return [{ type: 'plain', text }];
  return [
    ...profile.genres.map((genre): Query => ({ type: 'inGenre', text: `${text} ${genre}`, genre })),
    ...profile.artists.slice(0, ARTISTS_SEARCHED).map((artist): Query => ({ type: 'fromArtist', text: `${text} ${artist}`, artist })),
    { type: 'plain', text },
  ];
}

/**
 * The songs for a set, in order. The genre searches take turns, one song each, so a rock and hip-hop listener gets
 * both; within each, the artists the listener already plays come first. The artists' own songs that fit join the
 * turns (a few: they are a welcome extra, not the set). The ask on its own only fills what is left. No artist more
 * than [perArtist] times.
 */
export function merge(
  results: ReadonlyArray<readonly [Query, readonly Hit[]]>,
  knownArtists: ReadonlySet<string>,
  limit: number,
  perArtist: number = PER_ARTIST,
): string[] {
  const known = (h: Hit): boolean => knownArtists.has(fold(leadOf(h.artist)));
  const knownFirst = (hits: readonly Hit[]): Hit[] => [...hits].sort((a, b) => (known(a) ? 0 : 1) - (known(b) ? 0 : 1));

  const genreLists = results.filter(([q]) => q.type === 'inGenre').map(([, hits]) => knownFirst(hits));
  const artistLists: Hit[][] = [];
  for (const [q, hits] of results) {
    if (q.type !== 'fromArtist') continue;
    // Only the artist's own songs, and the first couple the search ranks for the ask.
    artistLists.push(hits.filter((h) => fold(h.artist).includes(fold(q.artist))).slice(0, ARTIST_HITS));
  }
  const artistList = interleave(artistLists).slice(0, ARTIST_EXTRAS);
  const plain = results.filter(([q]) => q.type === 'plain').flatMap(([, hits]) => knownFirst(hits));

  const out: string[] = [];
  const seen = new Set<string>();
  const counts = new Map<string, number>();
  const take = (h: Hit): boolean => {
    if (out.length >= limit || seen.has(h.id)) return false;
    const who = fold(leadOf(h.artist));
    const count = counts.get(who) ?? 0;
    if (count >= perArtist) return false;
    seen.add(h.id);
    counts.set(who, count + 1);
    out.push(h.id);
    return true;
  };

  // Round robin: one from each list in turn, until none has anything left to give.
  const turns: Hit[][] = [...genreLists, artistList];
  const cursors: number[] = turns.map(() => 0);
  let progressed = true;
  while (out.length < limit && progressed) {
    progressed = false;
    for (let i = 0; i < turns.length; i++) {
      const list = turns[i]!;
      while (cursors[i]! < list.length) {
        const hit = list[cursors[i]!]!;
        cursors[i] = cursors[i]! + 1;
        if (take(hit)) {
          progressed = true;
          break;
        }
      }
    }
  }
  for (const h of plain) take(h);
  return out;
}

/** Takes turns across [lists], one from each, front to back. */
function interleave<T>(lists: readonly (readonly T[])[]): T[] {
  const out: T[] = [];
  let i = 0;
  while (lists.some((l) => i < l.length)) {
    for (const l of lists) if (i < l.length) out.push(l[i] as T);
    i++;
  }
  return out;
}
