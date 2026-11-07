// Port of dev/auden/dj/DjWriter.kt: what the DJ says, built from facts the planner has just established.
//
// Every line is built from facts slotted into a template, so a line can only say something true about the
// block it introduces. A template whose slot has no fact is skipped rather than filled with a guess. Variety
// comes from many templates per situation and from remembering the last few used, so the same sentence does
// not come round twice in an evening.

import { understood, type DjBlock, type DjRequest, type DjTheme, type DjTrack } from './model';
import type { DjPhrases } from './phrases';

/** Plays this month that make an artist "lately"; plays ever that make them a regular. */
const RECENT_ARTIST_PLAYS = 5;
const REGULAR_ARTIST_PLAYS = 20;
/** How many recently used templates of one key are passed over. */
const TEMPLATE_MEMORY = 4;
const DAY_MS = 24 * 60 * 60 * 1000;

/** Values for a template's {slots}; null or blank means the slot has no fact, so the template is skipped. */
type Slots = Readonly<Record<string, string | null | undefined>>;

export interface DjWriterOptions {
  phrases: DjPhrases;
  /** "es", "en", …: the language numbers and decade names are said in. */
  language: string;
  seed: number;
  /** The hour, for naming the moment ("esta noche") in a set's reason. */
  hour?: number;
  /** Accepted for parity with the Kotlin writer, which does not use it either. */
  weekend?: boolean;
  /** Now, to say how long ago a song was last played. */
  nowMs?: number;
  /** What the listener has played of an artist: this month, and ever. Null when unknown. */
  artistPlays?: (artist: string) => { month: number; ever: number } | null;
}

/** Seeded PRNG (mulberry32): the same seed gives the same sequence, which is all the DJ needs. */
function mulberry32(seed: number): () => number {
  let a = seed >>> 0;
  return () => {
    a = (a + 0x6d2b79f5) >>> 0;
    let t = a;
    t = Math.imul(t ^ (t >>> 15), t | 1);
    t ^= t + Math.imul(t ^ (t >>> 7), t | 61);
    return ((t ^ (t >>> 14)) >>> 0) / 4294967296;
  };
}

const isBlank = (s: string | null | undefined): boolean => s == null || s.trim() === '';

/** The string with its first character uppercased, as Kotlin's replaceFirstChar does. */
const cap = (s: string): string => {
  const first = Array.from(s)[0] ?? '';
  return first.toUpperCase() + s.slice(first.length);
};

/** [template] with {slots} replaced, or null if any slot it names has no value. */
function fill(template: string, values: Slots): string | null {
  let missing = false;
  const out = template.replace(/\{(\w+)\}/g, (_, name: string) => {
    const v = values[name];
    if (isBlank(v)) {
      missing = true;
      return '';
    }
    return v ?? '';
  });
  return missing ? null : out;
}

/** The part of the day an hour falls in; greetings and moments are named by it. */
function dayPart(hour: number): 'morning' | 'afternoon' | 'evening' | 'night' {
  if (hour >= 5 && hour <= 11) return 'morning';
  if (hour >= 12 && hour <= 18) return 'afternoon';
  if (hour >= 19 && hour <= 22) return 'evening';
  return 'night';
}

const EN_WORDS = ['zero', 'one', 'two', 'three', 'four', 'five', 'six', 'seven', 'eight', 'nine', 'ten'];
const NUMBER_WORDS = new Map<string, readonly string[]>([
  ['es', ['cero', 'una', 'dos', 'tres', 'cuatro', 'cinco', 'seis', 'siete', 'ocho', 'nueve', 'diez']],
  ['pt', ['zero', 'uma', 'duas', 'três', 'quatro', 'cinco', 'seis', 'sete', 'oito', 'nove', 'dez']],
  ['fr', ['zéro', 'une', 'deux', 'trois', 'quatre', 'cinq', 'six', 'sept', 'huit', 'neuf', 'dix']],
  ['de', ['null', 'eins', 'zwei', 'drei', 'vier', 'fünf', 'sechs', 'sieben', 'acht', 'neun', 'zehn']],
  ['it', ['zero', 'una', 'due', 'tre', 'quattro', 'cinque', 'sei', 'sette', 'otto', 'nove', 'dieci']],
  ['en', EN_WORDS],
]);

export class DjWriter {
  private readonly phrases: DjPhrases;
  private readonly language: string;
  private readonly hour: number;
  private readonly nowMs: number;
  private readonly artistPlays: (artist: string) => { month: number; ever: number } | null;
  private readonly rng: () => number;
  private readonly lastUsed = new Map<string, number[]>();

  /** What the listener last asked for, if anything: a set that matches it says so. */
  request: DjRequest | null = null;

  /** The song that ended just before the set being introduced, for a line that picks up from it; null when unknown. */
  private prev: string | null = null;

  constructor(options: DjWriterOptions) {
    this.phrases = options.phrases;
    this.language = options.language;
    this.hour = options.hour ?? 12;
    this.nowMs = options.nowMs ?? 0;
    this.artistPlays = options.artistPlays ?? (() => null);
    this.rng = mulberry32(options.seed);
  }

  /** Said once, as the session starts: a greeting, then why the first set is what it is. */
  opening(_hour: number, first: DjBlock, request: DjRequest | null, relaxed: boolean): string {
    this.request = request;
    this.prev = null;
    const greeting = this.pick(`greeting_${dayPart(this.hour)}`);
    let note: string | null = null;
    if (request && !understood(request) && !isBlank(request.text)) note = this.pick('unrecognized');
    else if (relaxed) note = this.pick('relaxed');
    return join(greeting, note, this.introduce(first));
  }

  /** Said as a new set begins: why these songs, and the one it starts with. */
  blockIntro(block: DjBlock, previous: DjBlock | null): string {
    this.prev = previous?.tracks[previous.tracks.length - 1]?.title ?? null;
    return this.introduce(block) ?? '';
  }

  /**
   * A remark on a song, only when there is something about it worth saying: how often the listener has
   * played it, which playlist it is from, its album and year. A song with nothing to say gets nothing.
   */
  trackNote(track: DjTrack): string | null {
    const playlist = track.playlists?.[0];
    const options: (string | null)[] = [
      track.plays30 >= 3 ? this.pick('note_repeat', { title: track.title, n: String(track.plays30) }) : null,
      playlist != null ? this.pick('note_playlist', { title: track.title, playlist }) : null,
      track.year != null && !isBlank(track.album)
        ? this.pick('note_album_year', { title: track.title, album: track.album, year: String(track.year) })
        : null,
    ];
    const present = options.filter((o): o is string => o != null);
    if (present.length === 0) return null;
    return present[this.nextInt(present.length)] ?? null;
  }

  signOff(): string {
    return this.pick('signoff') ?? '';
  }

  // ── Introducing a set: the reason first, the first song second ─────────

  /**
   * Why the set is what it is, in a sentence about the listener, and then the song it opens on. The
   * reason is a fact the planner and the listening history both support. The opening song only closes the
   * sentence; it is never the announcement.
   */
  private introduce(block: DjBlock): string | null {
    const first = block.tracks[0];
    if (!first) return null;
    const theme = block.theme;
    const opener = this.openerText(first, theme);
    if (this.matchesRequest(theme)) {
      const what = this.what(theme);
      return this.pick('intro_request_match', { what, what_cap: what == null ? null : cap(what), opener });
    }
    switch (theme.type) {
      case 'artist': {
        const { month, ever } = this.artistPlays(theme.artist) ?? { month: 0, ever: 0 };
        const key =
          month >= RECENT_ARTIST_PLAYS ? 'intro_artist_recent'
            : ever >= REGULAR_ARTIST_PLAYS ? 'intro_artist_always'
              : 'intro_artist_saved';
        return this.pick(key, { artist: theme.artist, opener });
      }
      case 'mood': {
        const moment = this.pick(this.momentKey());
        return this.pick(`intro_mood_${theme.mood.toLowerCase()}`, {
          moment,
          moment_cap: moment == null ? null : cap(moment),
          opener,
        });
      }
      case 'favorites':
        return this.pick('intro_favorites', { opener });
      case 'rediscover': {
        const ago = this.agoText(first.lastPlayedAt);
        return this.pick('intro_rediscover', { ago, opener }) ?? this.pick('intro_rediscover_plain', { opener });
      }
      case 'fresh':
        return this.pick('intro_fresh', { opener });
      case 'discover': {
        const seed = block.tracks.map((t) => t.because).find((b) => b != null) ?? null;
        return this.pick('intro_discover', { seed, opener }) ?? this.pick('intro_discover_plain', { opener });
      }
      case 'playlist':
        return this.pick('intro_playlist', { playlist: theme.name, opener });
      case 'request':
        return this.pick('intro_search', { what: theme.text, taste: theme.taste, opener });
      case 'decade':
      case 'genre':
      case 'album':
        return this.pick('intro_theme', { what: this.what(theme), opener });
    }
  }

  /** "Song, by Artist"; just the title when the set is already that artist's. */
  private openerText(track: DjTrack, theme: DjTheme): string {
    if (theme.type === 'artist') return track.title;
    return this.pick('by_artist', { title: track.title, artist: track.artist }) ?? track.title;
  }

  /** The set as a thing the listener could have asked for: "music by X", "something calm"… */
  private what(theme: DjTheme): string | null {
    switch (theme.type) {
      case 'artist': return this.pick('what_artist', { artist: theme.artist });
      case 'decade': return this.pick('what_decade', { decade: this.decadeLabel(theme.decade) });
      case 'genre': return this.pick('what_genre', { genre: theme.genre });
      case 'album': return this.pick('what_album', { album: theme.album, artist: theme.artist });
      case 'mood': return this.pick(`mood_word_${theme.mood.toLowerCase()}`);
      case 'favorites': return this.pick('what_favorites');
      case 'fresh': return this.pick('what_fresh');
      case 'rediscover': return this.pick('what_rediscover');
      default: return null;
    }
  }

  /** Whether [theme] is what the listener asked for: the one case where "you asked for" is true. */
  private matchesRequest(theme: DjTheme): boolean {
    const r = this.request;
    if (!r || !understood(r)) return false;
    switch (theme.type) {
      case 'artist': return r.artist != null && r.artist.toLowerCase() === theme.artist.toLowerCase();
      case 'decade': return r.decade === theme.decade;
      case 'genre': return r.genre === theme.genre;
      case 'mood': return r.mood === theme.mood;
      case 'favorites': return !!r.favorites;
      case 'fresh': return !!r.fresh;
      case 'rediscover': return !!r.rediscover;
      default: return false;
    }
  }

  /** How long ago, in the unit a person would use; null when the song has no history. */
  private agoText(lastPlayedAt: number): string | null {
    if (lastPlayedAt <= 0 || this.nowMs <= 0) return null;
    const months = Math.trunc((this.nowMs - lastPlayedAt) / (30 * DAY_MS));
    if (months >= 12) return this.pick('ago_year');
    if (months >= 2) return this.pick('ago_months', { n: String(months) });
    return null;
  }

  private momentKey(): string {
    return `moment_${dayPart(this.hour)}`;
  }

  /** The theme as a noun phrase, for places that need to name it. */
  describe(theme: DjTheme): string | null {
    switch (theme.type) {
      case 'artist': return theme.artist;
      case 'decade': return this.decadeLabel(theme.decade);
      case 'genre': return theme.genre;
      case 'album': return theme.album;
      default: return null;
    }
  }

  // ── Templates ───────────────────────────────────────────────────────────

  /**
   * One template of [key] with its slots filled, never one used in the last few picks, never one with a
   * slot that has no value. Null when nothing fits.
   */
  private pick(key: string, slots: Slots = {}): string | null {
    const all = this.phrases.templates(key);
    if (all.length === 0) return null;
    // {prev} is available to every template: one that names it is passed over when there is no such song.
    const values: Slots = { prev: this.prev, ...slots };
    const usable: number[] = [];
    all.forEach((template, i) => {
      if (fill(template, values) != null) usable.push(i);
    });
    if (usable.length === 0) return null;
    let recent = this.lastUsed.get(key);
    if (!recent) {
      recent = [];
      this.lastUsed.set(key, recent);
    }
    const used = recent;
    const unused = usable.filter((i) => !used.includes(i));
    const fresh = unused.length > 0 ? unused : usable;
    const index = fresh[this.nextInt(fresh.length)];
    if (index === undefined) return null;
    const template = all[index];
    if (template === undefined) return null;
    recent.push(index);
    const keep = Math.max(0, Math.min(TEMPLATE_MEMORY, all.length - 1));
    while (recent.length > keep) recent.shift();
    return fill(template, values);
  }

  private nextInt(n: number): number {
    return Math.floor(this.rng() * n);
  }

  // ── Words ───────────────────────────────────────────────────────────────

  /** A number the way a person says it in a sentence about a few songs. */
  spell(n: number): string {
    const words = NUMBER_WORDS.get(this.language) ?? EN_WORDS;
    return words[n] ?? String(n);
  }

  /** "los 90", "the 90s", "les années 90", ... */
  decadeLabel(decade: number): string {
    const late = decade >= 2000;
    const short = late ? String(decade) : String(decade % 100);
    switch (this.language) {
      case 'es': return late ? `los ${decade}` : `los ${short}`;
      case 'pt': return late ? `os anos ${decade}` : `os anos ${short}`;
      case 'fr': return late ? `les années ${decade}` : `les années ${short}`;
      case 'de': return late ? `die ${decade}er` : `die ${short}er`;
      case 'it': return late ? `gli anni ${decade}` : `gli anni ${short}`;
      default: return `the ${short}s`;
    }
  }
}

/** Joins the parts that have something to say, with a space between. */
function join(...parts: (string | null)[]): string {
  return parts.filter((p): p is string => p != null && !isBlank(p)).join(' ');
}
