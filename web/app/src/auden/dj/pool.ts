import type { Track } from '@/data/music/types';
import { sourceOf } from '@/playback/source';
import { mergeHistory, playableNow, trackOfHistoryEntry, type HomeSources } from '../home/data';
import { type DjTrack, djTrack } from './model';

/** The songs the DJ may pick from (DjPool.kt): the library, what was ever heard, and the listener's playlists. */
export const POOL_CAP = 3_000;

export interface PoolSources extends Pick<HomeSources, 'months' | 'rows' | 'art' | 'hasFile'> {
  /** Playlists on this device: a title and the row ids it holds. */
  playlists: () => Promise<Array<{ title: string; rowIds: number[] }>>;
}

export interface Pool {
  tracks: DjTrack[];
  /** What the player plays for each DjTrack id (a local song keeps its source and synthetic id). */
  playable: Map<string, Track>;
}

const pad = (n: number) => String(n).padStart(2, '0');
const dayKey = (ms: number) => {
  const d = new Date(ms);
  return `${d.getFullYear()}-${pad(d.getMonth() + 1)}-${pad(d.getDate())}`;
};

/**
 * Library rows with a YouTube id or a file, plus everything ever heard, plus playlist songs. Deviation from the phone:
 * a file the listener added from a folder counts whatever its play count (Android asks 5 plays of a local-only file
 * because its source is the phone's whole media store; a web folder is a deliberate choice). Only what can play now
 * is kept, so a set never queues a song that fails.
 */
export async function buildPool(src: PoolSources, opts: { now: number; online: boolean }): Promise<Pool> {
  const { trackFromRow } = await import('../playlists/store');
  const [months, rows, playlists] = await Promise.all([src.months(), src.rows(), src.playlists()]);
  const rowById = new Map(rows.filter((r) => r.id != null).map((r) => [r.id!, r] as const));
  const history = new Map(mergeHistory(months).map((e) => [e.id, e] as const));
  const cutoff = dayKey(opts.now - 30 * 86_400_000);
  const playlistsOf = new Map<number, string[]>();
  for (const p of playlists) for (const id of p.rowIds) playlistsOf.set(id, [...(playlistsOf.get(id) ?? []), p.title]);

  const byId = new Map<string, DjTrack>();
  const playable = new Map<string, Track>();
  const heard = (id: string) => {
    const e = history.get(id);
    return e ? { plays: e.plays, plays30: Object.entries(e.days ?? {}).reduce((n, [day, s]) => (day >= cutoff ? n + s.plays : n), 0), lastPlayedAt: e.last } : { plays: 0, plays30: 0, lastPlayedAt: 0 };
  };
  const keep = (track: Track | null): track is Track => !!track && playableNow(track, opts.online, src.hasFile);

  for (const row of rows) {
    if (row.id == null) continue;
    const memberOf = playlistsOf.get(row.id);
    if (!row.localUri && !row.videoId && !memberOf) continue;
    const track = trackFromRow(row, src.art);
    if (!keep(track)) continue;
    const past = heard(track.videoId);
    const alsoLocal = row.localUri ? heard(`auden:local:${row.id}`) : null; // history written when it was a file only
    byId.set(
      track.videoId,
      djTrack({
        id: track.videoId,
        title: row.title,
        artist: row.artist,
        album: row.album,
        genre: row.genre,
        year: row.year,
        trackNumber: row.trackNumber,
        art: track.thumbnail,
        localUri: row.localUri,
        plays: past.plays + (alsoLocal?.plays ?? 0),
        plays30: past.plays30 + (alsoLocal?.plays30 ?? 0),
        lastPlayedAt: Math.max(past.lastPlayedAt, alsoLocal?.lastPlayedAt ?? 0),
        addedAt: row.addedAt,
        playlists: memberOf ?? [],
      }),
    );
    playable.set(track.videoId, track);
  }
  for (const entry of history.values()) {
    const track = trackOfHistoryEntry(entry, rowById, src.art);
    if (!keep(track) || byId.has(track.videoId)) continue;
    const past = heard(entry.id);
    byId.set(track.videoId, djTrack({ id: track.videoId, title: entry.title, artist: entry.artist, album: entry.album, art: track.thumbnail, ...past, localUri: sourceOf(track) === 'local' ? entry.id : null }));
    playable.set(track.videoId, track);
  }

  const tracks = [...byId.values()].sort((a, b) => b.plays * 2 + b.plays30 * 3 + (b.localUri ? 1 : 0) - (a.plays * 2 + a.plays30 * 3 + (a.localUri ? 1 : 0))).slice(0, POOL_CAP);
  const kept = new Set(tracks.map((t) => t.id));
  for (const id of playable.keys()) if (!kept.has(id)) playable.delete(id);
  return { tracks, playable };
}

/** The cross-session memory of what the DJ played lately (DjHistory.kt): it leans away from these, never forbids them. */
export const HISTORY_KEY = 'auden:dj:recent';
export const HISTORY_MAX = 400;

export const djHistory = {
  recent(): Set<string> {
    try {
      return new Set((localStorage.getItem(HISTORY_KEY) ?? '').split('\n').filter(Boolean));
    } catch {
      return new Set();
    }
  },
  record(id: string): void {
    try {
      const ids = (localStorage.getItem(HISTORY_KEY) ?? '').split('\n').filter((x) => x && x !== id);
      ids.push(id);
      localStorage.setItem(HISTORY_KEY, ids.slice(-HISTORY_MAX).join('\n'));
    } catch {
      // private mode: no memory across sessions
    }
  },
};
