/** LocalShelves.kt's rules: the shelves Home can build from what is on the device and what was played. */

import { primaryArtist } from '../stats/credit';

/** The shelves Home can build from what is on the device and what has been listened to. */
export const LocalShelfKind = {
  /** The last thirty days' most played. */
  LOOP: 'LOOP',
  /** Loved once, not heard for a long while. */
  REDISCOVER: 'REDISCOVER',
  /** Files and downloads that arrived most recently. */
  RECENTLY_ADDED: 'RECENTLY_ADDED',
  /** Playlists kept on this device, pinned first. */
  PLAYLISTS: 'PLAYLISTS',
  /** Songs of the artist heard most lately, from the library and from history. */
  ARTIST_MIX: 'ARTIST_MIX',
  /** A random pick of the files on the device: music that plays with no connection. */
  LIBRARY_SHUFFLE: 'LIBRARY_SHUFFLE',
} as const;

export type LocalShelfKind = (typeof LocalShelfKind)[keyof typeof LocalShelfKind];

/** Build order, which is also the order the shelves appear in before Home composes them. */
export const LOCAL_SHELF_KINDS: readonly LocalShelfKind[] = [
  LocalShelfKind.LOOP,
  LocalShelfKind.REDISCOVER,
  LocalShelfKind.RECENTLY_ADDED,
  LocalShelfKind.PLAYLISTS,
  LocalShelfKind.ARTIST_MIX,
  LocalShelfKind.LIBRARY_SHUFFLE,
];

/** One song's listening history, merged across every month kept. */
export interface PlayedTrack {
  id: string;
  title: string;
  artist: string;
  art: string | null;
  playsTotal: number;
  /** Plays in the thirty days up to now. */
  plays30: number;
  lastPlayedAt: number;
  /** The file, when the track is one: local tracks are identified by their uri. */
  localUri?: string | null;
}

/** A song the library holds. */
export interface LibraryTrack {
  id: string;
  title: string;
  artist: string;
  art: string | null;
  localUri: string | null;
  addedAt: number;
}

export interface PlaylistSummary {
  id: string;
  title: string;
  songCount: number;
  art: string | null;
  pinned: boolean;
}

/** What a shelf is called and how a count is worded, in the listener's language. */
export interface LocalShelfText {
  title(kind: LocalShelfKind, artist?: string): string;
  subtitle(kind: LocalShelfKind): string;
  songCount(n: number): string;
}

/** One item on a shelf: a track, or a playlist (whose id is the playlist's id). */
export interface ShelfItem {
  id: string;
  title: string;
  subtitle: string;
  art: string | null;
  kind: 'track' | 'playlist';
  localUri?: string | null;
}

/** A finished shelf, before Home decides where it goes. */
export interface LocalShelf {
  kind: LocalShelfKind;
  title: string;
  subtitle: string;
  items: ShelfItem[];
}

const DAY_MS = 24 * 60 * 60 * 1000;
export const SHELF_SIZE = 20;
export const MIN_ITEMS = 4;

/** Plays in the last month that make a song "on repeat" rather than just heard. */
export const LOOP_MIN_PLAYS = 2;

/** How many plays make a song loved, and how long without hearing it makes it forgotten. */
export const REDISCOVER_MIN_PLAYS = 5;
export const REDISCOVER_AFTER_DAYS = 90;

/** Days since the epoch, counted on the local calendar: the same day gives the same shelves. */
export function localDayNumber(ms: number): number {
  const tzOffsetMinutes = new Date(ms).getTimezoneOffset();
  return Math.floor((ms - tzOffsetMinutes * 60_000) / DAY_MS);
}

/** A small seeded PRNG (mulberry32): the same seed always gives the same sequence. */
export function mulberry32(seed: number): () => number {
  let a = seed | 0;
  return () => {
    a = (a + 0x6d2b79f5) | 0;
    let t = a;
    t = Math.imul(t ^ (t >>> 15), t | 1);
    t ^= t + Math.imul(t ^ (t >>> 7), t | 61);
    return ((t ^ (t >>> 14)) >>> 0) / 4294967296;
  };
}

/** A Fisher-Yates shuffle of a copy, driven by [mulberry32] so the order is fixed by the seed. */
export function seededShuffle<T>(items: readonly T[], seed: number): T[] {
  const random = mulberry32(seed);
  const out = items.slice();
  for (let i = out.length - 1; i > 0; i--) {
    const j = Math.floor(random() * (i + 1));
    const tmp = out[i] as T;
    out[i] = out[j] as T;
    out[j] = tmp;
  }
  return out;
}

/**
 * Builds the local shelves from listening history and the library.
 *
 * Pure, and handed the date: the same day gives the same shelves, so Home does not reshuffle
 * every time it is opened, and the rules are testable without a device. A shelf that has too
 * little to show is not built at all, which is how a new install stays empty of them rather than
 * showing three songs under a big heading.
 */
export function buildLocalShelves(
  played: PlayedTrack[],
  library: LibraryTrack[],
  playlists: PlaylistSummary[],
  now: number,
  text: LocalShelfText,
  dayOf: (ms: number) => number = localDayNumber,
): LocalShelf[] {
  const seed = dayOf(now);
  const built = [
    loop(played, text),
    rediscover(played, now, seed, text),
    recentlyAdded(library, text),
    playlistShelf(playlists, text),
    artistMix(played, library, seed, text),
    libraryShuffle(library, seed, text),
  ];
  return built.filter((shelf): shelf is LocalShelf => shelf !== null);
}

function loop(played: PlayedTrack[], text: LocalShelfText): LocalShelf | null {
  const tracks = played
    .filter((t) => t.plays30 >= LOOP_MIN_PLAYS)
    .sort((a, b) => b.plays30 - a.plays30 || b.lastPlayedAt - a.lastPlayedAt)
    .slice(0, SHELF_SIZE);
  return shelf(LocalShelfKind.LOOP, tracks.map(playedItem), text);
}

function rediscover(played: PlayedTrack[], now: number, seed: number, text: LocalShelfText): LocalShelf | null {
  const cutoff = now - REDISCOVER_AFTER_DAYS * DAY_MS;
  const tracks = seededShuffle(
    played
      .filter((t) => t.playsTotal >= REDISCOVER_MIN_PLAYS && t.lastPlayedAt >= 1 && t.lastPlayedAt < cutoff)
      .sort((a, b) => b.playsTotal - a.playsTotal)
      .slice(0, SHELF_SIZE * 2),
    seed + 1,
  ).slice(0, SHELF_SIZE);
  return shelf(LocalShelfKind.REDISCOVER, tracks.map(playedItem), text);
}

function recentlyAdded(library: LibraryTrack[], text: LocalShelfText): LocalShelf | null {
  const tracks = library
    .filter((t) => t.localUri !== null)
    .sort((a, b) => b.addedAt - a.addedAt)
    .slice(0, SHELF_SIZE);
  return shelf(LocalShelfKind.RECENTLY_ADDED, tracks.map(libraryItem), text);
}

function playlistShelf(playlists: PlaylistSummary[], text: LocalShelfText): LocalShelf | null {
  const items: ShelfItem[] = playlists
    .filter((p) => p.songCount > 0)
    .sort((a, b) => Number(b.pinned) - Number(a.pinned))
    .slice(0, SHELF_SIZE)
    .map((p) => ({
      id: p.id,
      title: p.title,
      subtitle: text.songCount(p.songCount),
      art: p.art,
      kind: 'playlist',
    }));
  // A single playlist is worth a shelf: unlike songs, there is nothing more to wait for.
  if (items.length === 0) return null;
  return {
    kind: LocalShelfKind.PLAYLISTS,
    title: text.title(LocalShelfKind.PLAYLISTS),
    subtitle: text.subtitle(LocalShelfKind.PLAYLISTS),
    items,
  };
}

interface ArtistGroup {
  /** The lead artist, in the case of the first track heard for them. */
  name: string;
  tracks: PlayedTrack[];
}

function artistMix(played: PlayedTrack[], library: LibraryTrack[], seed: number, text: LocalShelfText): LocalShelf | null {
  const groups = new Map<string, ArtistGroup>();
  for (const track of played) {
    const lead = leadArtist(track.artist);
    if (!lead) continue;
    const key = lead.toLowerCase();
    const group = groups.get(key);
    if (group) group.tracks.push(track);
    else groups.set(key, { name: lead, tracks: [track] });
  }

  // The artist heard most lately, then most overall; ties go to whoever was heard first.
  let top: { group: ArtistGroup; rank: Rank } | null = null;
  for (const group of groups.values()) {
    const rank = rankOf(group.tracks);
    if (top === null || compareRank(rank, top.rank) > 0) top = { group, rank };
  }
  if (top === null) return null;
  const { group: winner } = top;

  const name = winner.name;
  const fromHistory = winner.tracks.map(playedItem);
  const fromLibrary = library
    .filter((t) => leadArtist(t.artist).toLowerCase() === name.toLowerCase())
    .map(libraryItem);
  const items = seededShuffle(uniqueById([...fromHistory, ...fromLibrary]), seed + 2).slice(0, SHELF_SIZE);
  return shelf(LocalShelfKind.ARTIST_MIX, items, text, name);
}

function libraryShuffle(library: LibraryTrack[], seed: number, text: LocalShelfText): LocalShelf | null {
  const items = seededShuffle(
    library.filter((t) => t.localUri !== null),
    seed + 3,
  )
    .slice(0, SHELF_SIZE)
    .map(libraryItem);
  return shelf(LocalShelfKind.LIBRARY_SHUFFLE, items, text);
}

function shelf(kind: LocalShelfKind, items: ShelfItem[], text: LocalShelfText, artist?: string): LocalShelf | null {
  if (items.length < MIN_ITEMS) return null;
  return { kind, title: text.title(kind, artist), subtitle: text.subtitle(kind), items };
}

/** "A & B", "A, B" and "A feat. B" are all A's. An empty result means no artist at all. */
function leadArtist(credit: string): string {
  return primaryArtist(credit) ?? '';
}

function playedItem(t: PlayedTrack): ShelfItem {
  return {
    id: t.id,
    title: t.title,
    subtitle: t.artist,
    art: t.art,
    kind: 'track',
    localUri: t.localUri ?? (isUri(t.id) ? t.id : null),
  };
}

function libraryItem(t: LibraryTrack): ShelfItem {
  return { id: t.id, title: t.title, subtitle: t.artist, art: t.art, kind: 'track', localUri: t.localUri };
}

/** Local files are identified by their uri; nothing else here contains a scheme. */
function isUri(value: string): boolean {
  return value.startsWith('content://') || value.startsWith('file://');
}

function uniqueById(items: ShelfItem[]): ShelfItem[] {
  const seen = new Set<string>();
  return items.filter((item) => {
    if (seen.has(item.id)) return false;
    seen.add(item.id);
    return true;
  });
}

interface Rank {
  plays30: number;
  playsTotal: number;
  lastPlayedAt: number;
}

function rankOf(tracks: PlayedTrack[]): Rank {
  let plays30 = 0;
  let playsTotal = 0;
  let lastPlayedAt = Number.NEGATIVE_INFINITY;
  for (const t of tracks) {
    plays30 += t.plays30;
    playsTotal += t.playsTotal;
    lastPlayedAt = Math.max(lastPlayedAt, t.lastPlayedAt);
  }
  return { plays30, playsTotal, lastPlayedAt };
}

function compareRank(a: Rank, b: Rank): number {
  return a.plays30 - b.plays30 || a.playsTotal - b.playsTotal || a.lastPlayedAt - b.lastPlayedAt;
}

const EN = {
  titles: {
    LOOP: 'On repeat',
    REDISCOVER: 'Rediscover',
    RECENTLY_ADDED: 'Recently added',
    PLAYLISTS: 'Your playlists',
    ARTIST_MIX: 'Mix: {artist}',
    LIBRARY_SHUFFLE: 'From your library',
  },
  subtitles: {
    LOOP: 'What you have played most this month',
    REDISCOVER: 'Songs you loved and haven’t heard in a while',
    RECENTLY_ADDED: 'New in your library and downloads',
    PLAYLISTS: 'Saved on this device',
    ARTIST_MIX: 'From your library and your history',
    LIBRARY_SHUFFLE: 'Plays with no connection',
  },
  songCount: (n: number) => (n === 1 ? '1 song' : `${n} songs`),
} satisfies CopyTable;

const ES = {
  titles: {
    LOOP: 'En bucle',
    REDISCOVER: 'Redescubre',
    RECENTLY_ADDED: 'Añadido recientemente',
    PLAYLISTS: 'Tus playlists',
    ARTIST_MIX: 'Mix: {artist}',
    LIBRARY_SHUFFLE: 'De tu biblioteca',
  },
  subtitles: {
    LOOP: 'Lo que más has escuchado este mes',
    REDISCOVER: 'Canciones que te encantaban y hace tiempo que no escuchas',
    RECENTLY_ADDED: 'Lo nuevo en tu biblioteca y descargas',
    PLAYLISTS: 'Guardadas en este dispositivo',
    ARTIST_MIX: 'De tu biblioteca y tu historial',
    LIBRARY_SHUFFLE: 'Suena sin conexión',
  },
  songCount: (n: number) => (n === 1 ? '1 canción' : `${n} canciones`),
} satisfies CopyTable;

interface CopyTable {
  titles: Record<LocalShelfKind, string>;
  subtitles: Record<LocalShelfKind, string>;
  songCount: (n: number) => string;
}

/** The shelf wording in the listener's language. */
export function textFor(lang: 'en' | 'es'): LocalShelfText {
  const copy = lang === 'es' ? ES : EN;
  return {
    title: (kind, artist) => copy.titles[kind].replace('{artist}', () => artist ?? ''),
    subtitle: (kind) => copy.subtitles[kind],
    songCount: copy.songCount,
  };
}
