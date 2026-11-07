// Port of dev/auden/dj/DjPlanner.kt: chooses what the DJ plays, one set at a time.
//
// Two kinds of set. A request (typed, or "more of this") is followed literally: its songs are the ones
// that match it, and a set may repeat an artist or an album when the listener asked for exactly that. A
// surprise has no request, and is built to be listened to: a theme that fits the time of day and the week,
// a mix of what the listener knows and what they have not heard, no artist twice in a set, no artist again
// for two sets, and no album back to back. Coherence is by construction in both: a set is made only of songs
// that obey its theme, and the words the DJ says about it are true of every song in it.
//
// Nothing here touches disk, network or audio. Scoring reads the pool once per set.

import {
  DJ_MOODS,
  DjMood,
  MOOD_KEYWORDS,
  decadeOf,
  genreKeyOf,
  leadArtist,
  themeKey,
  themeKind,
  understood,
  type DjBlock,
  type DjRequest,
  type DjTheme,
  type DjTrack,
} from './model';

export const DEFAULT_BLOCK_SIZE = 8;
export const MIN_BLOCK = 4;
export const RECENT_THEMES = 4;
export const ARTIST_COOLDOWN_SETS = 2;
export const RECENT_PENALTY = 3.0;
export const FAMILIAR_SHARE = 0.7;
export const FAVORITE_MIN_PLAYS = 3;
export const REDISCOVER_MIN_PLAYS = 5;
export const DAY_MS = 24 * 60 * 60 * 1000;
export const REDISCOVER_AFTER_MS = 90 * DAY_MS;
export const FRESH_MS = 45 * DAY_MS;

const lower = (s: string): string => s.toLowerCase();
const notBlank = (s: string | null | undefined): s is string => s != null && s.trim() !== '';
const notNull = <T>(x: T | null | undefined): x is T => x != null;

/** Kotlin's String?.equals(other, ignoreCase = true): two nulls are equal, one null is not. */
function equalsIgnoreCase(a: string | null | undefined, b: string | null | undefined): boolean {
  if (a == null && b == null) return true;
  if (a == null || b == null) return false;
  return lower(a) === lower(b);
}

function groupBy<T, K>(items: readonly T[], key: (item: T) => K): Map<K, T[]> {
  const out = new Map<K, T[]>();
  for (const item of items) {
    const k = key(item);
    const list = out.get(k);
    if (list) list.push(item);
    else out.set(k, [item]);
  }
  return out;
}

function countBy<T>(items: readonly T[]): Map<T, number> {
  const out = new Map<T, number>();
  for (const item of items) out.set(item, (out.get(item) ?? 0) + 1);
  return out;
}

/**
 * A seeded generator of numbers in [0, 1): the same seed always gives the same sequence. Not java.util.Random's
 * sequence, which nothing depends on; only determinism per seed matters. Seeds are taken modulo 2^32.
 */
export function mulberry32(seed: number): () => number {
  let state = seed >>> 0;
  return () => {
    state = (state + 0x6d2b79f5) >>> 0;
    let t = state;
    t = Math.imul(t ^ (t >>> 15), t | 1);
    t ^= t + Math.imul(t ^ (t >>> 7), t | 61);
    return ((t ^ (t >>> 14)) >>> 0) / 4294967296;
  };
}

export interface DjPlannerOptions {
  pool: readonly DjTrack[];
  now: number;
  seed: number;
  blockSize?: number;
  /** What the listener asked for; null or omitted on a surprise. */
  request?: DjRequest | null;
  /** An artist's Last.fm genres, for reading a mood beyond the title. Empty when unknown. */
  genresOf?: (artist: string) => readonly string[];
  /** Songs the DJ played recently, in earlier sessions too: chosen last, so a new session sounds new. */
  recently?: ReadonlySet<string>;
}

interface Candidate {
  theme: DjTheme;
  tracks: DjTrack[];
}

export class DjPlanner {
  private readonly rng: () => number;
  private readonly pool: DjTrack[];
  private readonly now: number;
  private readonly blockSize: number;
  private readonly genresOf: (artist: string) => readonly string[];
  private readonly recently: ReadonlySet<string>;
  private readonly hour: number;
  private readonly weekend: boolean;
  private readonly used = new Set<string>();
  private readonly recentThemes: DjTheme[] = [];
  private readonly recentArtists: Set<string>[] = [];
  private readonly bias = new Map<string, number>();
  private readonly avoidedArtists = new Set<string>();
  private previous: DjBlock | null = null;
  private _request: DjRequest | null;
  private _relaxed = false;
  private scope: DjTrack[];

  constructor(opts: DjPlannerOptions) {
    this.rng = mulberry32(opts.seed);
    this.pool = [...opts.pool];
    this.now = opts.now;
    this.blockSize = opts.blockSize ?? DEFAULT_BLOCK_SIZE;
    this.genresOf = opts.genresOf ?? (() => []);
    this.recently = opts.recently ?? new Set<string>();
    const date = new Date(opts.now);
    this.hour = date.getHours();
    // Saturday or Sunday (Kotlin's dayOfWeek.value >= 6).
    const day = date.getDay();
    this.weekend = day === 0 || day === 6;
    this._request = opts.request ?? null;
    this.scope = this.scoped(this.pool, this._request);
  }

  /** What the listener asked for; null on a surprise. [setRequest] changes it from the next set on. */
  get request(): DjRequest | null {
    return this._request;
  }

  /** Set when the request could not be met as asked, so the DJ can say so rather than pretend. */
  get relaxed(): boolean {
    return this._relaxed;
  }

  setRequest(next: DjRequest | null): void {
    this._request = next;
    this.scope = this.scoped(this.pool, next);
    this._relaxed = false;
  }

  /** Songs found on the radio of something the listener knows; they join the pool for surprises. */
  addDiscoveries(found: readonly DjTrack[]): void {
    const known = new Set(this.pool.map((t) => t.id));
    for (const t of found) if (!known.has(t.id)) this.pool.push(t);
    this.scope = this.scoped(this.pool, this._request);
  }

  /** Frees songs of a set that was planned but never reached, so they may be chosen again. */
  release(ids: Iterable<string>): void {
    for (const id of ids) this.used.delete(id);
  }

  /** The listener wants more like [track]: its artist, genre and decade weigh more from here on. */
  more(track: DjTrack): void {
    this.boost(`artist:${lower(leadArtist(track))}`, 1.5);
    const genre = genreKeyOf(track);
    if (genre != null) this.boost(`genre:${genre}`, 1.3);
    const decade = decadeOf(track);
    if (decade != null) this.boost(`decade:${decade}`, 1.2);
  }

  /** The listener wants less like [track]: its artist leaves the session, its genre and decade weigh less. */
  less(track: DjTrack): void {
    this.avoidedArtists.add(lower(leadArtist(track)));
    const genre = genreKeyOf(track);
    if (genre != null) this.boost(`genre:${genre}`, 0.6);
    const decade = decadeOf(track);
    if (decade != null) this.boost(`decade:${decade}`, 0.8);
  }

  private boost(key: string, factor: number): void {
    this.bias.set(key, (this.bias.get(key) ?? 1.0) * factor);
  }

  /** The next set, or null when the pool has nothing left that makes a set of at least [MIN_BLOCK]. */
  next(): DjBlock | null {
    const surprise = this._request === null;
    // Themes whose artists are resting are passed over while any other theme can make a set; only
    // when none can does a theme go back to its artists, so a surprise never turns into a repeat by default.
    let candidates = this.candidatesFor(surprise, false);
    if (candidates.length === 0) candidates = this.candidatesFor(surprise, true);
    if (candidates.length === 0) return null;

    const maxAffinity = Math.max(...candidates.map((c) => this.affinity(c.tracks)), 1e-6);
    const scored = candidates
      .map((c) => ({ c, score: surprise ? this.surpriseScore(c, maxAffinity) : this.requestScore(c, maxAffinity) }))
      .sort((a, b) => b.score - a.score);

    // A weighted pick among the best three: the top theme most of the time, not every time.
    const top = scored.slice(0, 3);
    let roll = this.rng() * top.reduce((sum, x) => sum + x.score, 0);
    let chosen = top[0]!.c;
    for (const { c, score } of top) {
      roll -= score;
      if (roll <= 0) {
        chosen = c;
        break;
      }
    }

    const tracks = this.assemble(chosen.theme, chosen.tracks, surprise);
    for (const t of tracks) this.used.add(t.id);
    this.recentThemes.push(chosen.theme);
    while (this.recentThemes.length > RECENT_THEMES) this.recentThemes.shift();
    this.recentArtists.push(new Set(tracks.map((t) => lower(leadArtist(t)))));
    while (this.recentArtists.length > ARTIST_COOLDOWN_SETS) this.recentArtists.shift();
    const block: DjBlock = { theme: chosen.theme, tracks };
    this.previous = block;
    return block;
  }

  private surpriseScore(c: Candidate, maxAffinity: number): number {
    return (
      this.prior(c.theme) *
      (0.5 + (0.5 * this.affinity(c.tracks)) / maxAffinity) *
      this.novelty(c.theme) *
      (0.85 + 0.3 * this.rng())
    );
  }

  private requestScore(c: Candidate, maxAffinity: number): number {
    return (
      ((0.45 * this.affinity(c.tracks)) / maxAffinity +
        0.15 * this.novelty(c.theme) +
        0.25 * this.relatedness(c.tracks) +
        0.15 * this.requestMatch(c.theme)) *
      (this.bias.get(themeKey(c.theme)) ?? 1.0) *
      this.artistBias(c.theme) *
      (0.9 + 0.2 * this.rng())
    );
  }

  private candidatesFor(surprise: boolean, allowResting: boolean): Candidate[] {
    const out: Candidate[] = [];
    for (const theme of this.themes(surprise)) {
      const tracks = this.eligible(theme, surprise, allowResting);
      // A surprise needs enough different artists to give each one place; a theme that is one
      // artist's alone is not a surprise, whatever its songs.
      const enough = surprise ? this.distinctArtists(tracks) >= MIN_BLOCK : tracks.length >= MIN_BLOCK;
      if (enough) out.push({ theme, tracks });
    }
    return out;
  }

  // ── Themes ──────────────────────────────────────────────────────────────

  private themes(surprise: boolean): DjTheme[] {
    const out: DjTheme[] = [];
    if (surprise) {
      // Moods and moments, not artists: a surprise is a feeling, and the listener's own history.
      for (const mood of DJ_MOODS) out.push({ type: 'mood', mood });
      out.push({ type: 'favorites' }, { type: 'rediscover' }, { type: 'fresh' });
      if (this.pool.some((t) => t.discovery)) out.push({ type: 'discover' });
      // The listener's own playlists: a wide, personal source that is not just what they played most.
      for (const [name, n] of countBy(this.scope.flatMap((t) => t.playlists ?? []))) {
        if (n >= MIN_BLOCK) out.push({ type: 'playlist', name });
      }
    } else {
      for (const ts of groupBy(this.scope, (t) => lower(leadArtist(t))).values()) {
        if (ts.length >= MIN_BLOCK) out.push({ type: 'artist', artist: leadArtist(ts[0]!) });
      }
      for (const [decade, n] of countBy(this.scope.map(decadeOf).filter(notNull))) {
        if (n >= MIN_BLOCK) out.push({ type: 'decade', decade });
      }
      for (const [genre, n] of countBy(this.scope.map(genreKeyOf).filter(notNull))) {
        if (n >= MIN_BLOCK) out.push({ type: 'genre', genre });
      }
      for (const mood of DJ_MOODS) {
        if (this.scope.filter((t) => this.moodOf(t, mood)).length >= MIN_BLOCK) out.push({ type: 'mood', mood });
      }
      const albums = groupBy(
        this.scope.filter((t) => notBlank(t.album)),
        (t) => JSON.stringify([lower(t.album ?? ''), lower(leadArtist(t))]),
      );
      for (const ts of albums.values()) {
        if (ts.length >= MIN_BLOCK) {
          const first = ts[0]!;
          out.push({ type: 'album', album: first.album ?? '', artist: leadArtist(first) });
        }
      }
      out.push({ type: 'favorites' }, { type: 'rediscover' }, { type: 'fresh' });
    }
    // Not twice running, and not the same family back to back, unless there is no other choice.
    const recent = new Set(this.recentThemes.map(themeKey));
    const lastKind = this.previousKind();
    const request = this._request;
    const fresh = out.filter(
      (th) => !recent.has(themeKey(th)) && (themeKind(th) !== lastKind || (!surprise && request !== null && understood(request))),
    );
    return fresh.length > 0 ? fresh : out.filter((th) => !recent.has(themeKey(th)));
  }

  private previousKind(): string | null {
    return this.previous ? themeKind(this.previous.theme) : null;
  }

  private matches(theme: DjTheme, t: DjTrack): boolean {
    switch (theme.type) {
      case 'artist':
        return equalsIgnoreCase(leadArtist(t), theme.artist);
      case 'decade':
        return decadeOf(t) === theme.decade;
      case 'genre':
        return genreKeyOf(t) === theme.genre;
      case 'mood':
        return this.moodOf(t, theme.mood);
      case 'album':
        return equalsIgnoreCase(t.album, theme.album) && equalsIgnoreCase(leadArtist(t), theme.artist);
      case 'favorites':
        return t.plays >= FAVORITE_MIN_PLAYS;
      case 'rediscover':
        return t.plays >= REDISCOVER_MIN_PLAYS && t.lastPlayedAt >= 1 && t.lastPlayedAt < this.now - REDISCOVER_AFTER_MS;
      case 'fresh':
        return t.addedAt > 0 && this.now - t.addedAt <= FRESH_MS && t.localUri != null;
      case 'discover':
        return !!t.discovery;
      case 'playlist':
        return (t.playlists ?? []).some((p) => equalsIgnoreCase(p, theme.name));
      // Made by the session from a search, never chosen by the planner.
      case 'request':
        return false;
    }
  }

  /** The songs of [theme] still free to play; a surprise also leaves out artists of the last sets. */
  private eligible(theme: DjTheme, surprise: boolean, allowResting: boolean): DjTrack[] {
    const base = this.scope.filter(
      (t) => !this.used.has(t.id) && !this.avoidedArtists.has(lower(leadArtist(t))) && this.matches(theme, t),
    );
    if (!surprise) return base;
    // What the DJ played lately is left out while the theme has plenty else; with little, it stays in.
    const fresher = base.filter((t) => !this.recently.has(t.id));
    const open = this.distinctArtists(fresher) >= 2 * MIN_BLOCK ? fresher : base;
    // A surprise rests its artists: two sets back if the theme can still make a varied set from
    // the rest, else one set back. With [allowResting] off, a theme that cannot is left out.
    const sets = this.recentArtists;
    for (let depth = sets.length; depth >= 1; depth--) {
      const resting = new Set<string>();
      for (const s of sets.slice(-depth)) for (const a of s) resting.add(a);
      const rested = open.filter((t) => !resting.has(lower(leadArtist(t))));
      if (this.distinctArtists(rested) >= MIN_BLOCK) return rested;
    }
    return allowResting ? open : [];
  }

  // ── Scoring ─────────────────────────────────────────────────────────────

  /** How well [theme] suits this time of day and week. The one place the DJ's sense of the moment lives. */
  private prior(theme: DjTheme): number {
    const hour = this.hour;
    const weekend = this.weekend;
    const morning = hour >= 5 && hour <= 11;
    const afternoon = hour >= 12 && hour <= 18;
    const evening = hour >= 19 && hour <= 22;
    const notMorning = !morning; // Kotlin's hour !in 5..11
    switch (theme.type) {
      case 'mood':
        switch (theme.mood) {
          case DjMood.ENERGY:
            if (morning) return 1.0;
            if (afternoon) return weekend ? 0.9 : 0.6;
            if (evening) return weekend ? 0.9 : 0.5;
            return 0.2;
          case DjMood.FOCUS:
            if (afternoon && !weekend) return 1.0;
            if (morning && !weekend) return 0.7;
            return 0.3;
          case DjMood.CHILL:
            return evening || notMorning ? 1.0 : 0.5;
          case DjMood.ACOUSTIC:
            return evening || notMorning ? 0.9 : 0.6;
        }
        return 0.5;
      case 'favorites':
        return 0.6;
      case 'rediscover':
        return evening || notMorning ? 0.9 : 0.4;
      case 'fresh':
        return 0.6;
      case 'discover':
        return 0.7;
      case 'playlist':
        return 0.9;
      case 'request':
        return 0.0;
      case 'artist':
      case 'decade':
      case 'genre':
      case 'album':
        return 0.5;
    }
  }

  /** How much the listener has lived with the best of this theme's songs. */
  private affinity(tracks: readonly DjTrack[]): number {
    let sum = 0;
    for (const f of tracks.map(fit).sort((a, b) => b - a).slice(0, this.blockSize)) sum += f;
    return sum;
  }

  private novelty(theme: DjTheme): number {
    return themeKind(theme) === this.previousKind() ? 0.4 : 1.0;
  }

  /** Overlap of artists, genres and decades with the set before: a neighbour sounds like a continuation. */
  private relatedness(tracks: readonly DjTrack[]): number {
    const before = this.previous;
    if (before === null) return 0.5;
    const a = attributes(before.tracks);
    const b = attributes(tracks);
    const union = new Set([...a, ...b]).size;
    if (union === 0) return 0.0;
    let shared = 0;
    for (const x of a) if (b.has(x)) shared++;
    return shared / union;
  }

  private requestMatch(theme: DjTheme): number {
    const r = this._request;
    if (r === null) return 0.0;
    const hit = (ok: boolean) => (ok ? 1.0 : 0.0);
    switch (theme.type) {
      case 'favorites':
        return hit(!!r.favorites);
      case 'fresh':
        return hit(!!r.fresh);
      case 'rediscover':
        return hit(!!r.rediscover);
      case 'artist':
        return hit(equalsIgnoreCase(r.artist, theme.artist));
      case 'decade':
        return hit(r.decade === theme.decade);
      case 'genre':
        return hit(r.genre === theme.genre);
      case 'mood':
        return hit(r.mood === theme.mood);
      case 'album':
      case 'discover':
      case 'playlist':
      case 'request':
        return 0.0;
    }
  }

  private artistBias(theme: DjTheme): number {
    return theme.type === 'artist' ? (this.bias.get(`artist:${lower(theme.artist)}`) ?? 1.0) : 1.0;
  }

  // ── Assembling a set ────────────────────────────────────────────────────

  /**
   * The songs of a set, in a flowing order.
   *
   * A surprise: at most one song per artist, about seven in ten of them from what the listener
   * knows and the rest new to them, no album twice in a row. A request: the best matches, with the
   * artist cap the request itself implies.
   */
  private assemble(theme: DjTheme, eligible: DjTrack[], surprise: boolean): DjTrack[] {
    const artists = this.distinctArtists(eligible);
    const n = surprise ? Math.min(this.blockSize, artists) : Math.min(this.blockSize, eligible.length);
    if (theme.type === 'album') {
      return [...eligible]
        .sort((a, b) => (a.trackNumber ?? Number.MAX_SAFE_INTEGER) - (b.trackNumber ?? Number.MAX_SAFE_INTEGER))
        .slice(0, n);
    }
    if (theme.type === 'fresh') return [...eligible].sort((a, b) => b.addedAt - a.addedAt).slice(0, n);

    // A surprise gives each artist one place in its set. A request keeps the artist it names, and
    // otherwise allows two of an artist.
    const artistCap = surprise ? 1 : theme.type === 'artist' ? n : 2;
    // Each song's jitter is drawn once: a sort whose key changes between comparisons has no order.
    // Well-known songs are favoured, not guaranteed: a wide random spread keeps the top of the list from
    // being every set's answer, and songs heard lately sink.
    const ranked = eligible
      .map((t) => ({
        t,
        key: 0.35 * fit(t) + 1.2 * this.gaussian() - (this.recently.has(t.id) ? RECENT_PENALTY : 0),
      }))
      .sort((a, b) => b.key - a.key)
      .map((x) => x.t);
    const familiar = ranked.filter((t) => !t.discovery && t.plays > 0);
    const newer = ranked.filter((t) => !!t.discovery || t.plays === 0);
    const familiarTarget = surprise ? Math.round(n * FAMILIAR_SHARE) : n;

    const perArtist = new Map<string, number>();
    const picked: DjTrack[] = [];
    const take = (from: readonly DjTrack[], limit: number): void => {
      for (const t of from) {
        if (picked.length >= limit) break;
        if (picked.includes(t)) continue;
        const a = lower(leadArtist(t));
        const count = perArtist.get(a) ?? 0;
        if (count >= artistCap) continue;
        perArtist.set(a, count + 1);
        picked.push(t);
      }
    };
    if (surprise) {
      take(familiar, familiarTarget);
      take(newer, n);
      take(familiar, n);
    } else {
      take(ranked, n);
      // A request is allowed to come up short of its caps rather than repeat an artist past them.
      if (picked.length < n) {
        const extra = ranked.filter((t) => !picked.includes(t)).slice(0, n - picked.length);
        picked.push(...extra);
      }
    }
    return this.flow(picked, surprise);
  }

  private gaussian(): number {
    // Box-Muller from two uniforms; 1 - u keeps the logarithm finite.
    const u = 1 - this.rng();
    const v = this.rng();
    return Math.sqrt(-2 * Math.log(u)) * Math.cos(2 * Math.PI * v);
  }

  private distinctArtists(tracks: readonly DjTrack[]): number {
    return new Set(tracks.map((t) => lower(leadArtist(t)))).size;
  }

  /**
   * Opens on the best-known song, then walks to the nearest neighbour each time: never the same
   * album back to back, never the same artist back to back if there is any other way round, and a
   * tempo within reach when both are known.
   */
  private flow(tracks: DjTrack[], surprise: boolean): DjTrack[] {
    if (tracks.length <= 2) return tracks;
    const left = [...tracks];
    const out: DjTrack[] = [];
    // The first of the best-known songs, as Kotlin's maxByOrNull.
    let best = 0;
    for (let i = 1; i < left.length; i++) if (fit(left[i]!) > fit(left[best]!)) best = i;
    out.push(left.splice(best, 1)[0]!);
    while (left.length > 0) {
      const last = out[out.length - 1]!;
      // The first of the nearest, as Kotlin's minByOrNull.
      let nearest = 0;
      let nearestDistance = Infinity;
      for (let i = 0; i < left.length; i++) {
        const d = this.distance(last, left[i]!, surprise);
        if (d < nearestDistance) {
          nearest = i;
          nearestDistance = d;
        }
      }
      out.push(left.splice(nearest, 1)[0]!);
    }
    return out;
  }

  private distance(a: DjTrack, b: DjTrack, surprise: boolean): number {
    let d = 0;
    if (equalsIgnoreCase(leadArtist(a), leadArtist(b))) d += surprise ? 4.0 : 2.0;
    if (notBlank(a.album) && equalsIgnoreCase(a.album, b.album)) d += surprise ? 6.0 : 1.0;
    if (a.bpm != null && b.bpm != null) d += Math.abs(a.bpm - b.bpm) / 40.0;
    return d + 0.01 * this.rng();
  }

  // ── The request ─────────────────────────────────────────────────────────

  /** Sets [relaxed] as a side effect, as the Kotlin does: the last scoping decides it. */
  private scoped(pool: DjTrack[], request: DjRequest | null): DjTrack[] {
    if (request === null || !understood(request)) return pool;
    // Hard filters in order of how much the listener would give up: the file requirement first,
    // then the mood, the genre, the decade. Each step relaxes one, and the DJ says so.
    const steps: Array<(r: DjRequest) => DjRequest> = [
      (r) => r,
      (r) => ({ ...r, localOnly: false }),
      (r) => ({ ...r, localOnly: false, mood: null }),
      (r) => ({ ...r, localOnly: false, mood: null, genre: null }),
      (r) => ({ ...r, localOnly: false, mood: null, genre: null, decade: null }),
    ];
    for (let i = 0; i < steps.length; i++) {
      const r = steps[i]!(request);
      const filtered = pool.filter((t) => this.passes(t, r));
      if (filtered.length >= MIN_BLOCK) {
        this._relaxed = i > 0;
        return filtered;
      }
    }
    this._relaxed = true;
    return pool;
  }

  private passes(t: DjTrack, r: DjRequest): boolean {
    return (
      (r.artist == null || equalsIgnoreCase(leadArtist(t), r.artist)) &&
      (r.decade == null || decadeOf(t) === r.decade) &&
      (r.genre == null || genreKeyOf(t) === r.genre) &&
      (r.mood == null || this.moodOf(t, r.mood)) &&
      (!r.localOnly || t.localUri != null)
    );
  }

  /** A track's mood from its title, album, genre tag, and the artist's Last.fm genres. */
  private moodOf(t: DjTrack, mood: DjMood): boolean {
    const parts: Array<string | null | undefined> = [t.genre, t.title, t.album, ...this.genresOf(leadArtist(t))];
    const text = parts.filter(notNull).join(' ').toLowerCase();
    for (const keyword of MOOD_KEYWORDS[mood]) if (text.includes(keyword)) return true;
    return false;
  }
}

function fit(t: DjTrack): number {
  return Math.log(1.0 + t.plays) + 1.5 * Math.log(1.0 + t.plays30);
}

function attributes(tracks: readonly DjTrack[]): Set<string> {
  const out = new Set<string>();
  for (const t of tracks) {
    out.add(`artist:${lower(leadArtist(t))}`);
    const genre = genreKeyOf(t);
    if (genre != null) out.add(`genre:${genre}`);
    const decade = decadeOf(t);
    if (decade != null) out.add(`decade:${decade}`);
  }
  return out;
}
