import { create } from 'zustand';
import { LYRICS_SOURCES, orderedSources, type LyricsSourceId } from './sources';

/**
 * Which lyric providers are asked, and in what order (the desktop's lyric
 * settings). Kept in localStorage; a source added in a later build lands
 * where a fresh install would put it (sources.orderedSources).
 */
interface LyricsSettings {
  order: LyricsSourceId[];
  disabled: LyricsSourceId[];
  /** Keep looking past a line-timed answer for a word-timed one (LyricsRepository.prioritizeSyllableSync). */
  preferSyllables: boolean;
  /** PaxSenix's Spotify and Musixmatch routes need the user's own key. */
  paxsenixKey: string;
  /**
   * Shifts every song's lyrics, in ms, unless a song has its own (AppSettings.lyricsOffsetMs). Positive
   * shows the lyrics later: the cursor is the playhead minus the offset.
   */
  lyricsOffsetMs: number;
  /** Providers whose version is also kept when a song's lyrics are saved ("Extra lyrics to keep"). */
  extras: LyricsSourceId[];
  setEnabled: (id: LyricsSourceId, on: boolean) => void;
  move: (id: LyricsSourceId, by: -1 | 1) => void;
  setPreferSyllables: (on: boolean) => void;
  setPaxsenixKey: (key: string) => void;
  setLyricsOffsetMs: (ms: number) => void;
  setExtra: (id: LyricsSourceId, on: boolean) => void;
  reset: () => void;
}

const KEY = 'bitchord:lyrics';
type Saved = Pick<LyricsSettings, 'order' | 'disabled' | 'preferSyllables' | 'paxsenixKey' | 'lyricsOffsetMs' | 'extras'>;

/** The range and step of the offset, as on the phone. */
export const OFFSET_MIN_MS = -5000;
export const OFFSET_MAX_MS = 5000;
export const OFFSET_STEP_MS = 100;
export const clampOffset = (ms: number): number => Math.min(OFFSET_MAX_MS, Math.max(OFFSET_MIN_MS, Math.round(ms)));

const DEFAULTS: Saved = {
  order: LYRICS_SOURCES.map((s) => s.id),
  // The keyed routes do nothing without a key; off until one is entered.
  disabled: ['paxsenix-spotify', 'paxsenix-musixmatch'],
  preferSyllables: false,
  paxsenixKey: '',
  lyricsOffsetMs: 0,
  extras: [],
};

function load(): Saved {
  try {
    const saved = JSON.parse(localStorage.getItem(KEY) ?? '{}') as Partial<Saved>;
    return {
      ...DEFAULTS,
      ...saved,
      order: orderedSources(saved.order ?? DEFAULTS.order),
      lyricsOffsetMs: clampOffset(Number(saved.lyricsOffsetMs) || 0),
      extras: (saved.extras ?? []).filter((id) => LYRICS_SOURCES.some((s) => s.id === id)),
    };
  } catch {
    return DEFAULTS;
  }
}

export const useLyricsSettings = create<LyricsSettings>()((set, get) => {
  const save = (change: Partial<Saved>) => {
    set(change);
    const { order, disabled, preferSyllables, paxsenixKey, lyricsOffsetMs, extras } = get();
    localStorage.setItem(KEY, JSON.stringify({ order, disabled, preferSyllables, paxsenixKey, lyricsOffsetMs, extras }));
  };
  return {
    ...load(),
    setEnabled: (id, on) => save({ disabled: on ? get().disabled.filter((d) => d !== id) : [...new Set([...get().disabled, id])] }),
    move: (id, by) => {
      const order = [...get().order];
      const at = order.indexOf(id);
      const to = at + by;
      if (at < 0 || to < 0 || to >= order.length) return;
      [order[at], order[to]] = [order[to]!, order[at]!];
      save({ order });
    },
    setPreferSyllables: (preferSyllables) => save({ preferSyllables }),
    setPaxsenixKey: (paxsenixKey) => save({ paxsenixKey: paxsenixKey.trim() }),
    setLyricsOffsetMs: (ms) => save({ lyricsOffsetMs: clampOffset(ms) }),
    setExtra: (id, on) => save({ extras: on ? [...new Set([...get().extras, id])] : get().extras.filter((e) => e !== id) }),
    reset: () => save(DEFAULTS),
  };
});

/** The sources that will be asked, in the order they're asked. */
export function enabledSources(): LyricsSourceId[] {
  const { order, disabled } = useLyricsSettings.getState();
  return order.filter((id) => !disabled.includes(id));
}
