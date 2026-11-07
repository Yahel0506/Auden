import { splitArtists } from '@/auden/library/artistSplit';
import type { Track } from '@/data/music/types';

/**
 * The JSON Android keeps in `spotify_match.songJson` for a matched Spotify track
 * (SpotifyMatches.kt, class `Stored`). Both platforms read and write the same
 * string, so a match made on one reads back on the other. Field names and
 * nullability follow that class; unknown keys are ignored when reading.
 */
interface StoredSong {
  videoId: string;
  title: string;
  /** The credit as YouTube Music shows it, e.g. "A, B". */
  artist: string;
  thumbnailUrl?: string;
  /** "m:ss" or "h:mm:ss". */
  durationText?: string;
  albumName?: string;
  /** The first credited artist's channel id. */
  artistId?: string;
  albumId?: string;
  /** Kotlin non-null Boolean: always written, and a null here breaks Android's decode. */
  isVideo: boolean;
  isExplicit?: boolean;
}

/** "m:ss", or "h:mm:ss" once there is an hour. */
function formatDuration(totalSec: number): string {
  const sec = Math.round(totalSec);
  const h = Math.floor(sec / 3600);
  const m = Math.floor((sec % 3600) / 60);
  const s = String(sec % 60).padStart(2, '0');
  return h > 0 ? `${h}:${String(m).padStart(2, '0')}:${s}` : `${m}:${s}`;
}

/**
 * Seconds from "m:ss" or "h:mm:ss", or null for anything else. A zero length is
 * null too, the same as an unknown one, so a caller has one value to check.
 */
function parseDuration(text: unknown): number | null {
  if (typeof text !== 'string') return null;
  const parts = text.trim().split(':');
  if (parts.length < 2 || parts.length > 3 || !parts.every((p) => /^\d+$/.test(p))) return null;
  // Seconds stay below 60, and so do minutes in the h:mm:ss form. In the m:ss form minutes may pass 59:
  // Android writes a 75-minute track as "75:03" (MainActivity.formatDurationText), and that is a length.
  if (Number(parts[parts.length - 1]) >= 60 || (parts.length === 3 && Number(parts[1]) >= 60)) return null;
  // Horner's rule: "m:ss" gives m*60+ss, "h:mm:ss" gives (h*60+mm)*60+ss.
  const sec = parts.reduce((total, p) => total * 60 + Number(p), 0);
  return sec > 0 ? sec : null;
}

function nonEmptyString(value: unknown): string | null {
  return typeof value === 'string' && value !== '' ? value : null;
}

/**
 * The JSON to store for [track]. Null optionals are left out: JSON.stringify
 * drops `undefined`, which is what Android's missing-key default expects.
 */
export function trackToStoredJson(track: Track): string {
  const lead = track.artists[0];
  const stored: StoredSong = {
    videoId: track.videoId,
    title: track.title,
    artist: track.artists.map((a) => a.name).join(', '),
    artistId: lead?.id ?? undefined,
    albumName: track.album?.name ?? undefined,
    albumId: track.album?.id ?? undefined,
    thumbnailUrl: track.thumbnail ?? undefined,
    durationText: track.durationSec ? formatDuration(track.durationSec) : undefined,
    isExplicit: track.explicit,
    // A stored match is always a catalogue song; a music video is never one.
    isVideo: false,
  };
  return JSON.stringify(stored);
}

/**
 * The Track a stored song stands for, or null when [json] is not valid JSON or
 * has no videoId or title. Never throws.
 */
export function storedJsonToTrack(json: string): Track | null {
  let raw: unknown;
  try {
    raw = JSON.parse(json);
  } catch {
    return null;
  }
  if (typeof raw !== 'object' || raw === null || Array.isArray(raw)) return null;
  const s = raw as Record<string, unknown>;

  const videoId = nonEmptyString(s.videoId);
  const title = nonEmptyString(s.title);
  if (videoId === null || title === null) return null;

  // Android keeps one credit string; the first name is the lead, the only one
  // whose channel id is stored, so only it gets the id.
  const leadId = nonEmptyString(s.artistId);
  const artists = splitArtists(typeof s.artist === 'string' ? s.artist : '').map((name, i) => ({
    name,
    id: i === 0 ? leadId : null,
  }));

  const albumId = nonEmptyString(s.albumId);
  const albumName = nonEmptyString(s.albumName);
  // The album name is what the album header shows; without one the id alone is kept.
  const album = albumId === null && albumName === null ? null : { name: albumName ?? '', id: albumId };

  return {
    videoId,
    title,
    artists,
    album,
    durationSec: parseDuration(s.durationText),
    explicit: s.isExplicit === true,
    thumbnail: nonEmptyString(s.thumbnailUrl),
    index: null,
    plays: null,
  };
}
