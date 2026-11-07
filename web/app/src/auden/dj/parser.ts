// Port of dev/auden/dj/DjRequestParser.kt: turns "algo tranquilo de los 90" or "some focus music" into filters
// the planner can obey.
//
// Deliberately not a language model: it reads the request against what is actually in the library (its artists,
// its genres) and a small vocabulary of moods, decades and intentions in Spanish and English. What it cannot map
// ("sin letra" beyond instrumental genres, "para correr a las seis") is not guessed at; the DJ says it did not
// understand and chooses itself. It never names a song.

import { DjMood, genreKeyOf, leadArtist, type DjRequest, type DjTrack } from './model';

export function parseRequest(text: string, pool: readonly DjTrack[]): DjRequest {
  const q = fold(text);
  if (q.trim() === '') return { text };

  // A plain substring test first: the word-boundary check builds a regular expression, and a library
  // has thousands of artists, nearly all of which are not in the ask.
  const artist = longestNamedIn(
    q,
    pool.map((t) => leadArtist(t)),
    2,
  );

  const genre = longestNamedIn(
    q,
    pool.map((t) => genreKeyOf(t)).filter(notNull),
    3,
  );

  let mood: DjMood | null = null;
  if (hasAny(q, 'concentr', 'estudi', 'trabaj', 'focus', 'study', 'work', 'instrumental', 'sin letra', 'no lyrics', 'without lyrics')) {
    mood = DjMood.FOCUS;
  } else if (hasAny(q, 'relaj', 'tranquil', 'calm', 'chill', 'dormir', 'sleep', 'suave', 'soft', 'relax')) {
    mood = DjMood.CHILL;
  } else if (
    hasAny(q, 'energi', 'energ', 'entren', 'gym', 'workout', 'correr', 'running', 'fiesta', 'party', 'bailar', 'dance', 'animad', 'upbeat')
  ) {
    mood = DjMood.ENERGY;
  } else if (hasAny(q, 'acustic', 'acoustic', 'unplugged')) {
    mood = DjMood.ACOUSTIC;
  }

  return {
    text: text.trim(),
    artist,
    decade: decadeIn(q),
    genre,
    mood,
    favorites: hasAny(q, 'favorit', 'mejores', 'mas escuchad', 'best of', 'my best', 'most played', 'top '),
    fresh: hasAny(q, 'nuev', 'recient', 'recien', 'new ', 'recent', 'latest', 'fresh'),
    rediscover: hasAny(q, 'olvidad', 'clasic', 'antigu', 'forgotten', 'throwback', 'nostalg', 'rediscover', 'redescubr'),
    localOnly: hasAny(q, 'sin conexion', 'offline', 'descargad', 'downloaded', 'sin internet', 'no internet'),
  };
}

/**
 * Whether [text] names a genre of its own ("rock en español", "some jazz"): the one case where an ask is
 * not bent towards the listener's own genres. Mood and intention words are not genres.
 */
export function namesGenre(text: string): boolean {
  const q = fold(text);
  return GENRE_WORDS.some((w) => containsWord(q, w)) || GENRE_STEMS.some((s) => q.includes(s));
}

const GENRE_WORDS: readonly string[] = [
  'rock', 'pop', 'rap', 'hip hop', 'hiphop', 'hip-hop', 'trap', 'reggaeton', 'regueton', 'reggae', 'ska', 'jazz', 'blues',
  'metal', 'punk', 'indie', 'techno', 'house', 'edm', 'cumbia', 'salsa', 'bachata', 'merengue', 'banda', 'corridos', 'corrido',
  'norteno', 'norteña', 'mariachi', 'ranchera', 'rancheras', 'folk', 'country', 'soul', 'funk', 'disco', 'grunge', 'emo',
  'lofi', 'lo-fi', 'bossa nova', 'flamenco', 'tango', 'kpop', 'k-pop', 'jpop', 'latin', 'latino', 'balada', 'baladas',
  'gospel', 'opera', 'classical', 'clasica', 'r&b', 'rnb', 'dubstep', 'drill', 'alternativo', 'alternative', 'electronica',
  'electronic', 'electro', 'synthpop', 'shoegaze', 'hardcore', 'trova', 'boleros', 'bolero', 'vallenato', 'sertanejo',
];
const GENRE_STEMS: readonly string[] = ['hip hop', 'hip-hop', 'regional mexican', 'musica regional'];

/** "90s", "los 80", "años 70", "1985", "noventa", "the eighties". */
export function decadeIn(q: string): number | null {
  const full = /\b(19|20)(\d)\d\b/.exec(q);
  if (full) return Number(full[1]) * 100 + Number(full[2]) * 10;

  for (const m of q.matchAll(/(?:(anos|los|de|the|decada|decade|years)\s+)?\b(\d)0('?s)?\b/g)) {
    const d = Number(m[2]);
    // Without an "s" or a word like "los" or "años" it is only a number: "10 canciones".
    if ((m[1] ?? '') === '' && (m[3] ?? '') === '') continue;
    // "90s" and "los 90" are the nineties; "00s" is the 2000s and "10s" the 2010s.
    if (d >= 5 && d <= 9) return 1900 + d * 10;
    if (d >= 0 && d <= 2) return 2000 + d * 10;
  }

  const words: ReadonlyArray<readonly [string, number]> = [
    ['cincuenta', 1950], ['sesenta', 1960], ['setenta', 1970], ['ochenta', 1980], ['noventa', 1990],
    ['fifties', 1950], ['sixties', 1960], ['seventies', 1970], ['eighties', 1980], ['nineties', 1990],
  ];
  return words.find(([word]) => containsWord(q, word))?.[1] ?? null;
}

/** Lowercase without accents, so "Clásica" and "clasica" are one word. */
export function fold(text: string): string {
  return text.toLowerCase().normalize('NFD').replace(/\p{Mn}+/gu, '');
}

/** The longest of [names] (at least [minLength] long) that the folded [q] names as a word; ties keep the first. */
function longestNamedIn(q: string, names: readonly string[], minLength: number): string | null {
  let best: string | null = null;
  for (const name of new Set(names)) {
    if (name.length < minLength) continue;
    const f = fold(name);
    if (!(q.includes(f) && containsWord(q, f))) continue;
    if (best === null || name.length > best.length) best = name;
  }
  return best;
}

function hasAny(q: string, ...stems: string[]): boolean {
  return stems.some((s) => q.includes(s));
}

function containsWord(haystack: string, needle: string): boolean {
  if (needle.trim() === '') return false;
  return new RegExp(`(^|[^a-z0-9])${escapeRegExp(needle)}($|[^a-z0-9])`).test(haystack);
}

function escapeRegExp(s: string): string {
  return s.replace(/[.*+?^${}()|[\]\\]/g, '\\$&');
}

const notNull = <T>(x: T | null | undefined): x is T => x != null;
