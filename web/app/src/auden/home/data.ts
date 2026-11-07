import type { Track } from '@/data/music/types';
import { localRowIdOf, sourceOf } from '@/playback/source';
import { type StoredBucket, type TrackEntry, type TrackRow } from '../db/schema';
import { artKeyFromUrl } from '../library/scan/diff';
import { splitArtists } from '../library/artistSplit';
import { trackFromRow } from '../playlists/store';
import type { LibraryTrack, PlayedTrack, PlaylistSummary } from './shelves';

/** The home inputs, built from what is on this device. Sources are injected so it is testable without a browser. */

/** A real YouTube video id: the only shape of id from the stats that may be sent to YouTube. Anything else (a phone's content:// uri, a clip, a Spotify id, a blank) is dropped. */
const YOUTUBE_ID = /^[A-Za-z0-9_-]{11}$/;

export interface PlaylistInput {
  id: string;
  title: string;
  count: number;
  pinned: boolean;
  /** Already resolved to something an <img> can show. */
  art: string | null;
}

export interface HomeSources {
  months: () => Promise<StoredBucket[]>;
  rows: () => Promise<TrackRow[]>;
  playlists: () => Promise<PlaylistInput[]>;
  /** `auden-art://` key → blob url, for covers of local songs. */
  art: ReadonlyMap<string, string>;
  /** Whether a track has a file readable right now (linkIndex.hasLink). Null before the index exists. */
  hasFile: ((track: Track) => boolean) | null;
}

export interface HomeInputs {
  played: PlayedTrack[];
  library: LibraryTrack[];
  playlists: PlaylistSummary[];
  /** Every track id the shelves may show, as a playable Track (source kept: a local song stays local). */
  tracks: Map<string, Track>;
}

const pad = (n: number) => String(n).padStart(2, '0');
const dayKey = (ms: number) => {
  const d = new Date(ms);
  return `${d.getFullYear()}-${pad(d.getMonth() + 1)}-${pad(d.getDate())}`;
};

/** Merges the months' entries of each song: plays and day counts add, `last` is the latest, the text is the newest month's. */
export function mergeHistory(months: readonly StoredBucket[]): TrackEntry[] {
  const merged = new Map<string, TrackEntry>();
  for (const bucket of [...months].sort((a, b) => a.month.localeCompare(b.month))) {
    for (const entry of bucket.tracks) {
      const have = merged.get(entry.id);
      if (!have) {
        merged.set(entry.id, { ...entry, days: { ...(entry.days ?? {}) } });
        continue;
      }
      have.plays += entry.plays;
      have.ms += entry.ms;
      have.last = Math.max(have.last, entry.last);
      for (const [day, s] of Object.entries(entry.days ?? {})) {
        const d = have.days[day];
        have.days[day] = d ? { ms: d.ms + s.ms, plays: d.plays + s.plays } : { ...s };
      }
      if (entry.title) have.title = entry.title;
      if (entry.artist) have.artist = entry.artist;
      have.art = entry.art ?? have.art;
      have.album = entry.album ?? have.album;
      have.albumId = entry.albumId ?? have.albumId;
      have.artistId = entry.artistId ?? have.artistId;
    }
  }
  return [...merged.values()];
}

/** A song a shelf may play right now: a file needs its folder connected; a streamed song needs the network (or a linked file). */
export function playableNow(track: Track, online: boolean, hasFile: HomeSources['hasFile']): boolean {
  const file = !!hasFile && hasFile(track);
  return sourceOf(track) === 'local' ? file : file || online;
}

/**
 * The playable Track behind one listening entry, or null when it must not be used: only a real YouTube id or an
 * `auden:local:<id>` whose row still exists passes (a phone's content:// uri, a clip, a Spotify id, a blank do not).
 * A local id whose row now has a YouTube id becomes that YouTube song.
 */
export function trackOfHistoryEntry(entry: TrackEntry, rowById: ReadonlyMap<number, TrackRow>, art: ReadonlyMap<string, string>): Track | null {
  const rowId = localRowIdOf(entry.id);
  if (rowId !== null) {
    const row = rowById.get(Number(rowId));
    return row ? trackFromRow(row, art) : null;
  }
  if (!YOUTUBE_ID.test(entry.id)) return null;
  return {
    videoId: entry.id,
    title: entry.title,
    artists: (splitArtists(entry.artist).length ? splitArtists(entry.artist) : [entry.artist]).filter(Boolean).map((name, i) => ({ name, id: i === 0 ? entry.artistId : null })),
    album: entry.album ? { name: entry.album, id: entry.albumId } : null,
    durationSec: null,
    explicit: false,
    thumbnail: entry.art && !entry.art.startsWith('blob:') ? entry.art : null,
    index: null,
    plays: null,
  };
}

export async function loadHomeInputs(src: HomeSources, opts: { now: number; online: boolean }): Promise<HomeInputs> {
  const [months, rows, playlists] = await Promise.all([src.months(), src.rows(), src.playlists()]);
  const rowById = new Map(rows.filter((r) => r.id != null).map((r) => [r.id!, r] as const));
  const tracks = new Map<string, Track>();
  const cutoff = dayKey(opts.now - 30 * 86_400_000);

  const played: PlayedTrack[] = [];
  for (const entry of mergeHistory(months)) {
    const track = trackOfHistoryEntry(entry, rowById, src.art);
    // Offline, only what can play stays — BEFORE the shelves are built, so "at least four songs" still holds.
    if (!track || !playableNow(track, opts.online, src.hasFile)) continue;
    tracks.set(track.videoId, track);
    const plays30 = Object.entries(entry.days ?? {}).reduce((sum, [day, s]) => (day >= cutoff ? sum + s.plays : sum), 0);
    played.push({
      id: track.videoId,
      title: track.title || entry.title,
      artist: entry.artist || track.artists.map((a) => a.name).join(', '),
      art: track.thumbnail,
      playsTotal: entry.plays,
      plays30,
      lastPlayedAt: entry.last,
      localUri: sourceOf(track) === 'local' ? entry.id : null,
    });
  }

  const library: LibraryTrack[] = [];
  for (const row of rows) {
    if (!row.localUri) continue;
    const track = trackFromRow(row, src.art);
    if (!track || !playableNow(track, opts.online, src.hasFile)) continue;
    if (!tracks.has(track.videoId)) tracks.set(track.videoId, track);
    library.push({ id: track.videoId, title: row.title, artist: row.artist, art: track.thumbnail, localUri: row.localUri, addedAt: row.addedAt });
  }

  return {
    played,
    library,
    playlists: playlists.map((p) => ({ id: p.id, title: p.title, songCount: p.count, art: p.art, pinned: p.pinned })),
    tracks,
  };
}

/** A playlist card's cover: its stored cover or first song's art, resolved to a URL the page can show. */
export function resolveArt(url: string | null | undefined, art: ReadonlyMap<string, string>): string | null {
  if (!url) return null;
  const key = artKeyFromUrl(url);
  return key ? (art.get(key) ?? null) : url;
}
