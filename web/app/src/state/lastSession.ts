import type { Track } from '@/data/music/types';
import type { QueueEntry } from '@/playback/queue/timeline';
import { getPlayhead, sessionPlayer, usePlayer } from './player';

/**
 * What was playing, kept across reloads: the queue (the playing song, some of what came before and the
 * rest of what is next), where in the song it was, and shuffle's canonical order. It comes back paused at
 * that second; nothing plays until the listener presses play.
 */
const KEY = 'auden:last-session';
const VERSION = 1;
/** Rows kept before the playing one and after it: a very long queue does not fill the browser's storage. */
export const KEEP_BEHIND = 20;
export const KEEP_AHEAD = 280;

export interface SavedSession {
  v: typeof VERSION;
  entries: QueueEntry[];
  index: number;
  positionSec: number;
  canonical: [string, number][] | null;
}

/** Rows that cannot outlive the page: a DJ clip is audio held in memory, a party's queue is the party's. */
const keepable = (e: QueueEntry): boolean => e.track.source !== 'clip' && e.source?.title !== 'Listen Together';

/** The session worth keeping, or null when there is none (nothing playing, a clip, a party, a mirror). */
export function snapshotOf(
  state: Pick<ReturnType<typeof usePlayer.getState>, 'queue' | 'index' | 'nowPlaying' | 'mirror'>,
  positionSec: number,
  canonical: [string, number][] | null,
): SavedSession | null {
  const current = state.queue[state.index];
  if (state.mirror || !state.nowPlaying || !current || !keepable(current)) return null;
  const from = Math.max(0, state.index - KEEP_BEHIND);
  const entries = state.queue.slice(from, state.index + KEEP_AHEAD + 1).filter(keepable);
  const index = entries.findIndex((e) => e.entryId === current.entryId);
  if (index < 0) return null;
  const known = new Set(entries.map((e) => e.entryId));
  return {
    v: VERSION,
    entries,
    index,
    positionSec: Number.isFinite(positionSec) ? Math.max(0, positionSec) : 0,
    canonical: canonical?.filter(([id]) => known.has(id)) ?? null,
  };
}

const isTrack = (t: unknown): t is Track => {
  const o = t as Partial<Track> | null;
  return !!o && typeof o.videoId === 'string' && o.videoId.length > 0 && typeof o.title === 'string' && Array.isArray(o.artists);
};

/** A saved session read back, or null when it is not one of ours or is damaged. */
export function parseSession(text: string | null): SavedSession | null {
  if (!text) return null;
  try {
    const raw = JSON.parse(text) as Partial<SavedSession> | null;
    if (!raw || raw.v !== VERSION || !Array.isArray(raw.entries) || raw.entries.length === 0 || !Number.isInteger(raw.index)) return null;
    const index = raw.index as number;
    if (index < 0 || index >= raw.entries.length) return null;
    const entries = raw.entries.filter(
      (e): e is QueueEntry => !!e && typeof e.entryId === 'string' && isTrack(e.track) && (e.tier === 'context' || e.tier === 'user' || e.tier === 'autoplay'),
    );
    // A row dropped would shift the index: a session with a damaged row is not worth guessing at.
    if (entries.length !== raw.entries.length) return null;
    const positionSec = typeof raw.positionSec === 'number' && Number.isFinite(raw.positionSec) ? Math.max(0, raw.positionSec) : 0;
    const canonical = Array.isArray(raw.canonical) ? raw.canonical.filter((p): p is [string, number] => Array.isArray(p) && typeof p[0] === 'string' && typeof p[1] === 'number') : null;
    return { v: VERSION, entries, index, positionSec, canonical };
  } catch {
    return null;
  }
}

function read(): string | null {
  try {
    return localStorage.getItem(KEY);
  } catch {
    return null;
  }
}

function write(session: SavedSession | null): void {
  try {
    if (session) localStorage.setItem(KEY, JSON.stringify(session));
    else localStorage.removeItem(KEY);
  } catch {
    // Storage full or blocked: the session is lost on reload, nothing else.
  }
}

/** True while the last session is being put back: what is stored must not be overwritten by the half-loaded player. */
let restoring = false;

function save(): void {
  if (restoring) return;
  const state = usePlayer.getState();
  // While something is loading the playhead still belongs to the previous song.
  const sec = state.status === 'loading' ? 0 : (getPlayhead()?.sec ?? 0);
  write(snapshotOf(state, sec, sessionPlayer.canonical()));
}

/** Puts the last session back (once, paused) and keeps it up to date until the returned function is called. */
export function startLastSession(): () => void {
  const saved = parseSession(read());
  if (saved) {
    restoring = true;
    void sessionPlayer
      .restore(saved.entries, saved.index, saved.positionSec, saved.canonical)
      .catch(() => {})
      .finally(() => {
        restoring = false;
        save();
      });
  }

  let timer: ReturnType<typeof setTimeout> | null = null;
  const soon = () => {
    if (timer) clearTimeout(timer);
    timer = setTimeout(save, 600);
  };
  const off = usePlayer.subscribe((s, before) => {
    if (s.queue !== before.queue || s.index !== before.index || s.nowPlaying?.videoId !== before.nowPlaying?.videoId || s.shuffle !== before.shuffle) {
      // A queue emptied on purpose is forgotten; a reload during a restore must not wipe what is being restored.
      if (s.queue.length === 0 && before.queue.length > 0) write(null);
      else soon();
    }
  });
  const every = setInterval(() => {
    if (usePlayer.getState().status === 'playing') save();
  }, 5000);
  const onHide = () => {
    if (document.visibilityState === 'hidden') save();
  };
  document.addEventListener('visibilitychange', onHide);
  window.addEventListener('pagehide', save);
  return () => {
    off();
    clearInterval(every);
    if (timer) clearTimeout(timer);
    document.removeEventListener('visibilitychange', onHide);
    window.removeEventListener('pagehide', save);
  };
}
