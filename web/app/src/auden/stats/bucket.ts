import type { DayStats, NameEntry, StoredBucket, TrackEntry } from '../db/schema';
import { albumKey, primaryArtist } from './credit';

/**
 * ListeningStats.record() and prune() of the phone, over plain data. Listening is kept as one
 * bucket per month in exactly Android's StoredBucket shape, so the backup's `listening` list
 * moves between the two unchanged.
 */

/** What the recorder knows of the song being heard (Android's Song). */
export interface SongInfo {
  videoId: string;
  title: string;
  /** The credit as shown, e.g. "A & B". */
  artist: string;
  albumName: string | null;
  albumId: string | null;
  artistId: string | null;
  thumbnailUrl: string | null;
}

/** Per-month caps, as on the phone: nobody reaches them by listening. */
export const MAX_TRACKS = 600;
export const MAX_NAMES = 400;

export type Bucket = {
  month: string;
  tracks: Map<string, TrackEntry>;
  artists: Map<string, NameEntry>;
  albums: Map<string, NameEntry>;
  hours: number[];
  days: Record<number, number>;
  trackDays: Record<string, DayStats>;
  artistDays: Record<string, DayStats>;
  albumDays: Record<string, DayStats>;
};

/** Listening collected in memory, by month, until the next flush. */
export type Pending = Map<string, Bucket>;

const pad = (n: number) => String(n).padStart(2, '0');
export const monthKey = (at: Date) => `${at.getFullYear()}-${pad(at.getMonth() + 1)}`;
export const dayKey = (at: Date) => `${monthKey(at)}-${pad(at.getDate())}`;

function emptyBucket(month: string): Bucket {
  return {
    month,
    tracks: new Map(),
    artists: new Map(),
    albums: new Map(),
    hours: new Array<number>(24).fill(0),
    days: {},
    trackDays: {},
    artistDays: {},
    albumDays: {},
  };
}

function addDay(days: Record<string, DayStats>, key: string, ms: number, play: boolean) {
  const d = (days[key] ??= { ms: 0, plays: 0 });
  d.ms += ms;
  if (play) d.plays++;
}

function bump(entry: { ms: number; plays: number; days: Record<string, DayStats> }, day: string, ms: number, play: boolean) {
  entry.ms += ms;
  if (play) entry.plays++;
  addDay(entry.days, day, ms, play);
}

/** Adds one sample of listening: [playedMs] of [song], and a play when [countsAsPlay]. */
export function record(pending: Pending, song: SongInfo, playedMs: number, countsAsPlay: boolean, at: Date): void {
  if ((playedMs <= 0 && !countsAsPlay) || !song.videoId.trim()) return;
  const month = monthKey(at);
  const day = dayKey(at);
  let bucket = pending.get(month);
  if (!bucket) pending.set(month, (bucket = emptyBucket(month)));

  let track = bucket.tracks.get(song.videoId);
  if (!track) {
    track = {
      id: song.videoId,
      title: song.title,
      artist: song.artist,
      album: song.albumName,
      albumId: song.albumId,
      artistId: song.artistId,
      art: song.thumbnailUrl,
      ms: 0,
      plays: 0,
      last: 0,
      days: {},
    };
    bucket.tracks.set(song.videoId, track);
  }
  track.last = at.getTime();
  // A track queued from search can reach the player before its album is known.
  track.album ??= song.albumName;
  track.albumId ??= song.albumId;
  track.artistId ??= song.artistId;
  track.art ??= song.thumbnailUrl;
  bump(track, day, playedMs, countsAsPlay);
  addDay(bucket.trackDays, day, playedMs, countsAsPlay);

  // The lead artist, not the credit as a string: "A & B" is not a third artist.
  const lead = primaryArtist(song.artist);
  if (lead) {
    const key = lead.toLowerCase();
    let artist = bucket.artists.get(key);
    if (!artist) {
      artist = { name: lead, sub: null, art: song.thumbnailUrl, id: null, ms: 0, plays: 0, key: null, days: {} };
      bucket.artists.set(key, artist);
    }
    bump(artist, day, playedMs, countsAsPlay);
    artist.id ??= song.artistId;
    addDay(bucket.artistDays, day, playedMs, countsAsPlay);
  }

  const albumName = song.albumName?.trim();
  if (albumName) {
    const key = albumKey(song.albumId, albumName, song.artist);
    let album = bucket.albums.get(key);
    if (!album) {
      album = { name: albumName, sub: song.artist, art: song.thumbnailUrl, id: null, ms: 0, plays: 0, key: null, days: {} };
      bucket.albums.set(key, album);
    }
    bump(album, day, playedMs, countsAsPlay);
    album.id ??= song.albumId;
    addDay(bucket.albumDays, day, playedMs, countsAsPlay);
  }

  bucket.hours[at.getHours()]! += playedMs;
  const dom = at.getDate();
  bucket.days[dom] = (bucket.days[dom] ?? 0) + playedMs;
}

function absorbDays(into: Record<string, DayStats>, from: Record<string, DayStats>) {
  for (const [k, v] of Object.entries(from)) {
    const d = (into[k] ??= { ms: 0, plays: 0 });
    d.ms += v.ms;
    d.plays += v.plays;
  }
}

function absorbTrack(into: TrackEntry, from: TrackEntry) {
  into.ms += from.ms;
  into.plays += from.plays;
  into.last = Math.max(into.last, from.last);
  into.album ??= from.album;
  into.albumId ??= from.albumId;
  into.artistId ??= from.artistId;
  into.art ??= from.art;
  absorbDays(into.days, from.days);
}

function absorbName(into: NameEntry, from: NameEntry) {
  into.ms += from.ms;
  into.plays += from.plays;
  into.art ??= from.art;
  into.id ??= from.id;
  absorbDays(into.days, from.days);
}

/** As the phone re-keys artists on load: a stored whole credit folds into its lead artist. */
const artistKey = (e: NameEntry) => (primaryArtist(e.name) ?? e.name).toLowerCase();

/** Keeps the [max] entries listened to longest: what survives is what was actually heard. */
function trimTo<T extends { ms: number }>(map: Map<string, T>, max: number): void {
  if (map.size <= max) return;
  [...map.entries()]
    .sort((a, b) => b[1].ms - a[1].ms)
    .slice(max)
    .forEach(([k]) => map.delete(k));
}

/**
 * [pending]'s listening added to the month's stored bucket (or a new one). Keys are recomputed from
 * the entries, as the phone does on load, and entries that land on one key are added together.
 */
export function mergeBucket(storedBucket: StoredBucket | undefined, pending: Bucket): StoredBucket {
  const tracks = new Map<string, TrackEntry>();
  const artists = new Map<string, NameEntry>();
  const albums = new Map<string, NameEntry>();
  const addTrack = (t: TrackEntry) => {
    const have = tracks.get(t.id);
    if (have) absorbTrack(have, t);
    else tracks.set(t.id, structuredClone(t));
  };
  const addArtist = (a: NameEntry) => {
    const k = artistKey(a);
    const have = artists.get(k);
    if (have) absorbName(have, a);
    else artists.set(k, structuredClone(a));
  };
  const addAlbum = (a: NameEntry) => {
    const k = albumKey(a.id, a.name, a.sub ?? '');
    const have = albums.get(k);
    if (have) absorbName(have, a);
    else albums.set(k, structuredClone(a));
  };

  const hours = new Array<number>(24).fill(0);
  const days: Record<number, number> = {};
  const trackDays: Record<string, DayStats> = {};
  const artistDays: Record<string, DayStats> = {};
  const albumDays: Record<string, DayStats> = {};

  if (storedBucket) {
    storedBucket.tracks.forEach(addTrack);
    storedBucket.artists.forEach(addArtist);
    storedBucket.albums.forEach(addAlbum);
    storedBucket.hours.forEach((v, i) => (hours[i] = v));
    for (const [d, v] of Object.entries(storedBucket.days)) days[Number(d)] = v;
    absorbDays(trackDays, storedBucket.trackDays ?? {});
    absorbDays(artistDays, storedBucket.artistDays ?? {});
    absorbDays(albumDays, storedBucket.albumDays ?? {});
  }
  pending.tracks.forEach(addTrack);
  pending.artists.forEach(addArtist);
  pending.albums.forEach(addAlbum);
  pending.hours.forEach((v, i) => (hours[i]! += v));
  for (const [d, v] of Object.entries(pending.days)) days[Number(d)] = (days[Number(d)] ?? 0) + v;
  absorbDays(trackDays, pending.trackDays);
  absorbDays(artistDays, pending.artistDays);
  absorbDays(albumDays, pending.albumDays);

  trimTo(tracks, MAX_TRACKS);
  trimTo(artists, MAX_NAMES);
  trimTo(albums, MAX_NAMES);

  return {
    version: storedBucket?.version ?? 1,
    month: pending.month,
    tracks: [...tracks.values()],
    artists: [...artists.values()],
    albums: [...albums.values()],
    hours,
    days,
    trackDays,
    artistDays,
    albumDays,
  };
}

export function hasListening(pending: Pending): boolean {
  return pending.size > 0;
}
