// Port of dev/auden/dj/DjModel.kt: what the DJ chooses from and what a set of songs has in common.

/** A song the DJ may play, with only what choosing needs. */
export interface DjTrack {
  /** The video id, or `auden:local:<id>` for a track that is only a file. */
  id: string;
  title: string;
  artist: string;
  album?: string | null;
  genre?: string | null;
  year?: number | null;
  trackNumber?: number | null;
  art?: string | null;
  localUri?: string | null;
  plays: number;
  plays30: number;
  lastPlayedAt: number;
  addedAt: number;
  /** Known only for tracks the analyzer has already seen; never computed for the DJ's sake. */
  bpm?: number | null;
  /** Found by the DJ on YouTube Music's radio of something the listener knows: new to them, and on-theme. */
  discovery?: boolean;
  /** The listener's playlists this song is in. */
  playlists?: string[];
  /** For a discovery: the artist of the song it was found from. */
  because?: string | null;
}

/** A DjTrack with the defaults Kotlin gives (plays 0 …). */
export const djTrack = (t: Pick<DjTrack, 'id' | 'title' | 'artist'> & Partial<DjTrack>): DjTrack => ({ plays: 0, plays30: 0, lastPlayedAt: 0, addedAt: 0, ...t });

const LEAD_SPLIT = / & |, | feat\. | ft\. | x | y /i;
/** The lead of a credit: "A & B", "A, B" and "A feat. B" are all A's. */
export const leadOf = (credit: string): string => (credit.split(LEAD_SPLIT)[0] ?? credit).trim();
export const leadArtist = (t: Pick<DjTrack, 'artist'>): string => leadOf(t.artist);
export const decadeOf = (t: Pick<DjTrack, 'year'>): number | null => (t.year != null && t.year >= 1900 && t.year <= 2099 ? Math.floor(t.year / 10) * 10 : null);

/** "Rock; Pop" and "rock/pop" are rock: the first named genre, lowercased. */
export function normalizeGenre(raw: string): string | null {
  const first = raw.split(/[;/,\u0000]/)[0]?.trim().toLowerCase();
  return first ? first : null;
}
export const genreKeyOf = (t: Pick<DjTrack, 'genre'>): string | null => (t.genre ? normalizeGenre(t.genre) : null);

export const DjMood = {
  FOCUS: 'FOCUS',
  CHILL: 'CHILL',
  ENERGY: 'ENERGY',
  ACOUSTIC: 'ACOUSTIC',
} as const;
export type DjMood = (typeof DjMood)[keyof typeof DjMood];

export const MOOD_KEYWORDS: Record<DjMood, ReadonlySet<string>> = {
  FOCUS: new Set(['instrumental', 'ambient', 'piano', 'classical', 'clásica', 'clasica', 'lo-fi', 'lofi', 'study', 'focus', 'minimal', 'post-rock', 'soundtrack']),
  CHILL: new Set(['chill', 'lounge', 'downtempo', 'bossa', 'soft', 'jazz', 'r&b', 'soul', 'trip hop', 'easy listening', 'dream pop', 'indie pop']),
  ENERGY: new Set(['rock', 'metal', 'punk', 'dance', 'edm', 'electro', 'house', 'techno', 'hip hop', 'hip-hop', 'rap', 'reggaeton', 'trap', 'workout', 'hard', 'drum', 'pop', 'funk']),
  ACOUSTIC: new Set(['acoustic', 'acústico', 'acustico', 'unplugged', 'folk', 'singer-songwriter', 'country', 'bluegrass']),
};
export const DJ_MOODS: readonly DjMood[] = ['FOCUS', 'CHILL', 'ENERGY', 'ACOUSTIC'];

/** What a block of songs has in common: the thing the DJ says out loud, and the rule the songs obey. */
export type DjTheme =
  | { type: 'artist'; artist: string }
  | { type: 'decade'; decade: number }
  | { type: 'genre'; genre: string }
  | { type: 'mood'; mood: DjMood }
  | { type: 'album'; album: string; artist: string }
  | { type: 'favorites' }
  | { type: 'rediscover' }
  | { type: 'playlist'; name: string }
  | { type: 'fresh' }
  | { type: 'discover' }
  /** What the listener typed, as YouTube Music understood it: songs found by searching it. */
  | { type: 'request'; text: string; taste?: string | null };

const lower = (s: string) => s.toLowerCase();

/** Identity, for not repeating a theme and for the listener's "more / less of this". */
export function themeKey(theme: DjTheme): string {
  switch (theme.type) {
    case 'artist': return `artist:${lower(theme.artist)}`;
    case 'decade': return `decade:${theme.decade}`;
    case 'genre': return `genre:${theme.genre}`;
    case 'mood': return `mood:${theme.mood}`;
    case 'album': return `album:${lower(theme.artist)}:${lower(theme.album)}`;
    case 'favorites': return 'favorites';
    case 'rediscover': return 'rediscover';
    case 'playlist': return `playlist:${lower(theme.name)}`;
    case 'fresh': return 'fresh';
    case 'discover': return 'discover';
    case 'request': return `request:${lower(theme.text)}`;
  }
}

/** The family a theme belongs to; two blocks in a row are never of the same one unless asked for. */
export const themeKind = (theme: DjTheme): string => themeKey(theme).split(':')[0]!;

/** A run of songs that all obey one theme. */
export interface DjBlock {
  theme: DjTheme;
  tracks: DjTrack[];
}

/** What the listener asked for, understood. Every filter is optional and they combine. */
export interface DjRequest {
  text: string;
  artist?: string | null;
  decade?: number | null;
  genre?: string | null;
  mood?: DjMood | null;
  favorites?: boolean;
  fresh?: boolean;
  rediscover?: boolean;
  localOnly?: boolean;
}

/** True when the text said something the DJ could act on. */
export const understood = (r: DjRequest): boolean => !!(r.artist || r.decade != null || r.genre || r.mood || r.favorites || r.fresh || r.rediscover || r.localOnly);
