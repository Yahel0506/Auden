import { describe, expect, it } from 'vitest';
import en from './phrases.en.json';
import es from './phrases.es.json';
import { DJ_MOODS, DjMood, djTrack, type DjBlock, type DjRequest, type DjTheme, type DjTrack } from './model';
import { phrasesFor, tableFrom } from './phrases';
import { DjWriter } from './writer';

// Mirrors app/src/test/java/dev/auden/dj/DjTest.kt, the DjWriter tests.

const day = 24 * 60 * 60 * 1000;
const now = 2000 * day;

const t = (id: string, artist = 'Ana', extra: Partial<DjTrack> = {}): DjTrack =>
  djTrack({ id, title: `T${id}`, artist, ...extra });

/** Ana (rock, 90s), Bo (pop, 2000s), Cy (jazz, 90s). */
const library: DjTrack[] = [
  ...[1, 2, 3, 4, 5, 6].map((n) => t(`a${n}`, 'Ana', { album: 'Alpha', genre: 'Rock', year: 1990 + n, plays: 10 - n, plays30: 2 })),
  ...[1, 2, 3, 4, 5, 6].map((n) => t(`b${n}`, 'Bo', { album: 'Beta', genre: 'Pop', year: 2000 + n, plays: 5, plays30: 1 })),
  ...[1, 2, 3, 4, 5, 6].map((n) => t(`c${n}`, 'Cy', { genre: 'Jazz', year: 1990 + n, plays: 3 })),
];

const phrases = tableFrom({
  greeting_night: ['Late one.'],
  unrecognized: ['Didn’t catch that.'],
  intro_artist_recent: ['You’ve played a lot of {artist} lately, so: {opener}.'],
  intro_artist_always: ['{artist} is one of your regulars, so: {opener}.'],
  intro_artist_saved: ['You saved a few by {artist}, so: {opener}.'],
  intro_favorites: ['Songs you repeat, like {opener}.'],
  intro_rediscover: ['It’s been {ago} since you played these: {opener}.'],
  intro_rediscover_plain: ['You used to play these: {opener}.'],
  intro_discover: ['You play a lot of {seed}, so something new: {opener}.'],
  intro_discover_plain: ['Something new: {opener}.'],
  intro_playlist: ['From your playlist {playlist}: {opener}.'],
  intro_mood_chill: ['{moment_cap} calls for something easy: {opener}.'],
  intro_request_match: ['You asked for {what}: {opener}.'],
  intro_theme: ['{what}: {opener}.'],
  what_artist: ['music by {artist}'],
  mood_word_chill: ['something calm'],
  moment_night: ['tonight'],
  ago_months: ['{n} months'],
  ago_year: ['over a year'],
  by_artist: ['{title} by {artist}'],
  note_year: ['{title} from {year}.'],
  note_plain: ['{title}, {artist}.'],
});

const writer = (plays: Record<string, { month: number; ever: number }> = {}): DjWriter =>
  new DjWriter({
    phrases,
    language: 'en',
    seed: 1,
    hour: 23,
    nowMs: now,
    artistPlays: (artist) => plays[artist] ?? null,
  });

const block = (theme: DjTheme, tracks: DjTrack[]): DjBlock => ({ theme, tracks });

const titleOf = (tracks: DjTrack[]): string => tracks[0]?.title ?? '';

describe('DjWriter', () => {
  it('an artist set gives the reason before the song', () => {
    const ana = library.filter((x) => x.artist === 'Ana').slice(0, 4);
    const recent = writer({ Ana: { month: 9, ever: 40 } }).blockIntro(block({ type: 'artist', artist: 'Ana' }, ana), null);
    expect(recent.startsWith('You’ve played a lot of Ana lately'), recent).toBe(true);
    const regular = writer({ Ana: { month: 1, ever: 40 } }).blockIntro(block({ type: 'artist', artist: 'Ana' }, ana), null);
    expect(regular, regular).toContain('one of your regulars');
    const saved = writer({ Ana: { month: 0, ever: 3 } }).blockIntro(block({ type: 'artist', artist: 'Ana' }, ana), null);
    expect(saved, saved).toContain('saved a few');
    // The song closes the sentence, and carries no artist when the set is already theirs.
    expect(recent.endsWith(`${titleOf(ana)}.`), recent).toBe(true);
  });

  it('another artist’s song names its artist', () => {
    const mixed = library.filter((x) => x.artist === 'Bo').slice(0, 4);
    const line = writer().blockIntro(block({ type: 'favorites' }, mixed), null);
    expect(line, line).toContain(`${titleOf(mixed)} by Bo`);
  });

  it('a rediscover set says how long it has been', () => {
    const old = library.filter((x) => x.artist === 'Cy').slice(0, 4).map((x) => ({ ...x, lastPlayedAt: now - 120 * day }));
    const line = writer().blockIntro(block({ type: 'rediscover' }, old), null);
    expect(line, line).toContain('4 months');
    const unknown = writer().blockIntro(block({ type: 'rediscover' }, old.map((x) => ({ ...x, lastPlayedAt: 0 }))), null);
    expect(unknown, unknown).toContain('You used to play these');
  });

  it('a discovery names the artist it was found from', () => {
    const found = [1, 2, 3, 4].map((n) => t(`n${n}`, `Stranger${n}`, { discovery: true, because: 'Ana' }));
    const line = writer().blockIntro(block({ type: 'discover' }, found), null);
    expect(line, line).toContain('You play a lot of Ana');
    const plain = writer().blockIntro(block({ type: 'discover' }, found.map((x) => ({ ...x, because: null }))), null);
    expect(plain.startsWith('Something new'), plain).toBe(true);
  });

  it('a mood set names the moment', () => {
    const line = writer().blockIntro(block({ type: 'mood', mood: DjMood.CHILL }, library.slice(0, 4)), null);
    expect(line.startsWith('Tonight calls for something easy'), line).toBe(true);
  });

  it('“you asked for” is said only when the listener did', () => {
    const ana = library.filter((x) => x.artist === 'Ana').slice(0, 4);
    const w = writer();
    w.request = { text: 'Ana', artist: 'Ana' };
    const asked = w.blockIntro(block({ type: 'artist', artist: 'Ana' }, ana), null);
    expect(asked.startsWith('You asked for music by Ana'), asked).toBe(true);
    const surprise = writer().blockIntro(block({ type: 'artist', artist: 'Ana' }, ana), null);
    expect(surprise, surprise).not.toContain('You asked for');
  });

  it('a playlist set names the playlist', () => {
    const tracks = [1, 2, 3, 4].map((n) => t(`pl${n}`, `Singer${n}`, { playlists: ['Road trip'] }));
    const line = writer().blockIntro(block({ type: 'playlist', name: 'Road trip' }, tracks), null);
    expect(line, line).toContain('Road trip');
  });

  it('no line leaves a slot unfilled', () => {
    const themes: DjTheme[] = [
      { type: 'favorites' },
      { type: 'rediscover' },
      { type: 'fresh' },
      { type: 'discover' },
      { type: 'mood', mood: DjMood.CHILL },
    ];
    for (const theme of themes) {
      const line = writer().blockIntro(block(theme, library.slice(0, 4)), null);
      expect(line.includes('{') || line.includes('}'), line).toBe(false);
    }
  });

  it('the decade is said the way each language says it', () => {
    const es1 = new DjWriter({ phrases, language: 'es', seed: 1 });
    expect(es1.decadeLabel(1990)).toBe('los 90');
    expect(es1.decadeLabel(2000)).toBe('los 2000');
    const en1 = new DjWriter({ phrases, language: 'en', seed: 1 });
    expect(en1.decadeLabel(1990)).toBe('the 90s');
    expect(en1.decadeLabel(2010)).toBe('the 2010s');
  });

  it('the opening greets, then says when it did not understand', () => {
    const ana = library.filter((x) => x.artist === 'Ana').slice(0, 4);
    const line = writer().opening(23, block({ type: 'artist', artist: 'Ana' }, ana), { text: 'blah' }, false);
    expect(line.startsWith('Late one. Didn’t catch that.'), line).toBe(true);
  });

  it('a song with nothing to say gets no remark', () => {
    const table = tableFrom({
      note_repeat: ['{title}: {n} times.'],
      note_playlist: ['{title} from {playlist}.'],
      note_album_year: ['{title}, {album}, {year}.'],
    });
    const w = new DjWriter({ phrases: table, language: 'en', seed: 1 });
    expect(w.trackNote(t('bare', 'Ana'))).toBeNull();
    expect(w.trackNote(t('r', 'Ana', { title: 'Trep', plays30: 4 }))).toBe('Trep: 4 times.');
    expect(w.trackNote(t('p', 'Ana', { title: 'Tp', playlists: ['Road'] }))).toBe('Tp from Road.');
  });

  it('spells small numbers in each language, and falls back to digits', () => {
    expect(new DjWriter({ phrases, language: 'es', seed: 1 }).spell(2)).toBe('dos');
    expect(new DjWriter({ phrases, language: 'de', seed: 1 }).spell(3)).toBe('drei');
    expect(new DjWriter({ phrases, language: 'en', seed: 1 }).spell(10)).toBe('ten');
    expect(new DjWriter({ phrases, language: 'en', seed: 1 }).spell(11)).toBe('11');
  });

  it('the same seed gives the same lines', () => {
    const ana = library.filter((x) => x.artist === 'Ana').slice(0, 4);
    const lines = (seed: number) => {
      const w = new DjWriter({ phrases, language: 'en', seed, hour: 23, nowMs: now });
      return [1, 2, 3].map((n) => w.blockIntro(block({ type: 'artist', artist: 'Ana' }, ana.slice(0, n)), null));
    };
    expect(lines(7)).toEqual(lines(7));
  });
});

describe('phrase tables', () => {
  // The 45 keys of app/src/main/java/dev/auden/dj/AndroidPhrases.kt, in the same order.
  const KOTLIN_KEYS = [
    'greeting_morning', 'greeting_afternoon', 'greeting_evening', 'greeting_night',
    'relaxed', 'unrecognized',
    'intro_artist_recent', 'intro_artist_always', 'intro_artist_saved',
    'intro_favorites', 'intro_rediscover', 'intro_rediscover_plain', 'intro_fresh',
    'intro_discover', 'intro_discover_plain', 'intro_playlist',
    'intro_mood_focus', 'intro_mood_chill', 'intro_mood_energy', 'intro_mood_acoustic',
    'intro_request_match', 'intro_search', 'intro_theme',
    'what_artist', 'what_decade', 'what_genre', 'what_album', 'what_favorites', 'what_fresh', 'what_rediscover',
    'mood_word_focus', 'mood_word_chill', 'mood_word_energy', 'mood_word_acoustic',
    'ago_months', 'ago_year', 'by_artist',
    'note_repeat', 'note_playlist', 'note_album_year',
    'signoff',
    'moment_morning', 'moment_afternoon', 'moment_evening', 'moment_night',
  ];

  const tables: Record<string, Record<string, readonly string[]>> = { en, es };

  it('lists the 45 keys of AndroidPhrases.kt', () => {
    expect(KOTLIN_KEYS).toHaveLength(45);
    expect(new Set(KOTLIN_KEYS).size).toBe(45);
  });

  for (const [name, table] of Object.entries(tables)) {
    it(`${name} has exactly the Android keys`, () => {
      expect(Object.keys(table).sort()).toEqual([...KOTLIN_KEYS].sort());
    });

    it(`${name} has at least one template per key, none blank`, () => {
      for (const [key, templates] of Object.entries(table)) {
        expect(templates.length, key).toBeGreaterThan(0);
        for (const template of templates) expect(template.trim(), key).not.toBe('');
      }
    });
  }

  it('the two languages have the same key set', () => {
    expect(Object.keys(es).sort()).toEqual(Object.keys(en).sort());
  });

  it('phrasesFor reads the same tables', () => {
    expect(phrasesFor('en').templates('greeting_night').length).toBeGreaterThan(0);
    expect(phrasesFor('es').templates('greeting_night').length).toBeGreaterThan(0);
    expect(phrasesFor('en').templates('no_such_key')).toEqual([]);
    expect(phrasesFor('en').templates('constructor')).toEqual([]);
  });

  it('no generated line contains a brace, in any theme, mood or hour, in either language', () => {
    // Rich songs have every fact a line can name; bare songs have none of them.
    const rich = [1, 2, 3, 4].map((n) =>
      djTrack({
        id: `r${n}`, title: `R${n}`, artist: 'Ana', album: 'Alpha', genre: 'Rock', year: 1995,
        plays: 9, plays30: 4, lastPlayedAt: now - 120 * day, playlists: ['Road trip'],
        discovery: true, because: 'Ana', localUri: `content://r${n}`,
      }),
    );
    const bare = [1, 2, 3, 4].map((n) => djTrack({ id: `b${n}`, title: `B${n}`, artist: `Stranger${n}` }));

    const themes: DjTheme[] = [
      { type: 'artist', artist: 'Ana' },
      { type: 'decade', decade: 1990 },
      { type: 'genre', genre: 'rock' },
      ...DJ_MOODS.map((mood): DjTheme => ({ type: 'mood', mood })),
      { type: 'album', album: 'Alpha', artist: 'Ana' },
      { type: 'favorites' },
      { type: 'rediscover' },
      { type: 'playlist', name: 'Road trip' },
      { type: 'fresh' },
      { type: 'discover' },
      { type: 'request', text: 'algo tranquilo', taste: 'Rock' },
      { type: 'request', text: 'algo tranquilo', taste: null },
    ];
    const requestFor = (theme: DjTheme): DjRequest | null => {
      switch (theme.type) {
        case 'artist': return { text: theme.artist, artist: theme.artist };
        case 'decade': return { text: 'decade', decade: theme.decade };
        case 'genre': return { text: theme.genre, genre: theme.genre };
        case 'mood': return { text: theme.mood, mood: theme.mood };
        case 'favorites': return { text: 'favorites', favorites: true };
        case 'fresh': return { text: 'fresh', fresh: true };
        case 'rediscover': return { text: 'rediscover', rediscover: true };
        default: return null;
      }
    };
    const artistPlays: ((artist: string) => { month: number; ever: number } | null)[] = [
      () => ({ month: 9, ever: 40 }),
      () => ({ month: 1, ever: 40 }),
      () => ({ month: 0, ever: 3 }),
      () => null,
    ];

    const offenders: string[] = [];
    let generated = 0;
    const check = (line: string | null | undefined, where: string) => {
      if (line == null || line === '') return;
      generated += 1;
      if (line.includes('{') || line.includes('}')) offenders.push(`${where}: ${line}`);
    };

    for (const lang of ['es', 'en'] as const) {
      const phraseSet = phrasesFor(lang);
      for (let hour = 0; hour < 24; hour++) {
        for (const theme of themes) {
          for (const plays of artistPlays) {
            for (const matched of [false, true]) {
              for (const tracks of [rich, bare]) {
                for (const previous of [null, block(theme, bare)]) {
                  const where = `${lang} h${hour} ${JSON.stringify(theme)} matched=${matched} prev=${previous ? 'yes' : 'no'}`;
                  const w = new DjWriter({ phrases: phraseSet, language: lang, seed: hour + 1, hour, nowMs: now, artistPlays: plays });
                  const req = matched ? requestFor(theme) : null;
                  const b = block(theme, tracks);
                  check(w.opening(hour, b, { text: 'qwerty' }, true), `${where} opening-unrecognized`);
                  check(w.opening(hour, b, req, false), `${where} opening`);
                  check(w.blockIntro(b, previous), `${where} intro`);
                  if (req) w.request = req;
                  check(w.blockIntro(b, previous), `${where} intro-request`);
                  for (const track of tracks) check(w.trackNote(track), `${where} note`);
                  check(w.signOff(), `${where} signoff`);
                }
              }
            }
          }
        }
      }
    }

    expect(generated).toBeGreaterThan(5000);
    expect(offenders.slice(0, 20)).toEqual([]);
  });
});
