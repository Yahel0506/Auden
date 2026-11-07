import type { SpotifyAlbum, SpotifyArtist, SpotifyPlaylist, SpotifyTrack } from '@bitchord/shared/bridge';
import { best, type MatchCandidate } from '@/data/match/trackMatcher';
import type { Track } from '@/data/music/types';
import { getBridge } from '@/extension/bridge';
import { withSpotifyRetry } from './retry';

/**
 * Spotify through the extension (prompt §7.1): the library, a playlist's
 * tracks, and matching each one to its YouTube Music song. Ports
 * SpotifyLibrary.kt's paging and SpotifyImporter.matchTrack.
 */

export const LIKED_ID = 'liked';

function bridge() {
  const b = getBridge();
  if (!b) throw new Error('The Auden extension is not installed');
  return b;
}

/**
 * Every entry of a paged Spotify list, [read] one page at a time. Each page is retried on its own
 * (withSpotifyRetry), so a throttle in the middle asks for the same offset again rather than starting over.
 */
async function pagedList<T>(read: (offset: number) => Promise<{ items: T[]; total: number; raw: number }>, signal?: AbortSignal): Promise<T[]> {
  const out: T[] = [];
  for (let offset = 0; ; ) {
    const page = await withSpotifyRetry(() => read(offset), { signal });
    out.push(...page.items);
    offset += page.raw;
    if (page.raw === 0 || offset >= page.total) break;
  }
  return out;
}

/** Every playlist in the library, Liked Songs first, however many pages it takes. */
export function fetchSpotifyPlaylists(signal?: AbortSignal): Promise<SpotifyPlaylist[]> {
  return pagedList((offset) => bridge().call('spotify.playlists', { offset }, { timeoutMs: 30_000, signal }), signal);
}

/** The saved albums (needs an extension with the `spotify.library` capability). */
export function fetchSpotifyAlbums(signal?: AbortSignal): Promise<SpotifyAlbum[]> {
  return pagedList((offset) => bridge().call('spotify.albums', { offset }, { timeoutMs: 30_000, signal }), signal);
}

/** The followed artists (needs an extension with the `spotify.library` capability). */
export function fetchSpotifyArtists(signal?: AbortSignal): Promise<SpotifyArtist[]> {
  return pagedList((offset) => bridge().call('spotify.artists', { offset }, { timeoutMs: 30_000, signal }), signal);
}

export function fetchSpotifyPlaylistInfo(playlistId: string, signal?: AbortSignal) {
  return withSpotifyRetry(() => bridge().call('spotify.playlist', { playlistId }, { timeoutMs: 30_000, signal }), { signal });
}

/** Every track of a playlist; [onPage] gets the running list after each page, so a long one fills in. */
export async function fetchSpotifyTracks(
  playlistId: string,
  onPage: (soFar: SpotifyTrack[], total: number, nextOffset: number) => void,
  { from = 0, soFar = [] as SpotifyTrack[], signal }: { from?: number; soFar?: SpotifyTrack[]; signal?: AbortSignal } = {},
): Promise<SpotifyTrack[]> {
  const out: SpotifyTrack[] = [...soFar];
  for (let offset = from; ; ) {
    const page = await withSpotifyRetry(() => bridge().call('spotify.tracks', { playlistId, offset }, { timeoutMs: 30_000, signal }), { signal });
    out.push(...page.items);
    offset += page.raw;
    onPage([...out], page.total, offset);
    if (page.raw === 0 || offset >= page.total) break;
  }
  return out;
}

/** A Spotify row as a list row, before its YouTube Music version is known. */
export function pendingTrack(track: SpotifyTrack, unavailable: 'matching' | 'missing' = 'matching'): Track {
  return {
    videoId: `spotify:${track.id}`,
    title: track.title,
    artists: track.artist ? track.artist.split(', ').map((name) => ({ name, id: null })) : [],
    album: track.album ? { name: track.album, id: null } : null,
    durationSec: track.durationMs > 0 ? Math.round(track.durationMs / 1000) : null,
    explicit: false,
    thumbnail: track.imageUrl,
    index: null,
    plays: null,
    spotifyId: track.id,
    unavailable,
  };
}

export function candidateOf(track: Track): MatchCandidate {
  return {
    title: track.title,
    artist: track.artists.map((a) => a.name).join(', '),
    durationSec: track.durationSec,
    album: track.album?.name ?? null,
    explicit: track.explicit,
  };
}

/**
 * The YouTube Music song that is [track], or null if there is none: the remembered match, else a search
 * (matches.ts). Kept for callers that want just the song (saving a Spotify playlist as a local copy);
 * a failed search throws rather than reading as "not found", so a caller can try again later.
 * Loaded on demand because matches.ts reads this file's `candidateOf`.
 */
export async function matchToYouTube(track: SpotifyTrack): Promise<Track | null> {
  const { resolveSpotifyTrack } = await import('./matches');
  const result = await resolveSpotifyTrack(track);
  if (result.status === 'error') throw result.error;
  return result.status === 'found' ? { ...result.track, spotifyId: track.id } : null;
}

// ---- Canvas (SpotifyCanvas / DesktopSpotifyCanvas) ---------------------------------

const canvases = new Map<string, string | null>();

/**
 * The looping clip Spotify shows behind [track], or null. The Spotify
 * counterpart is found with the same matcher, so a cover or a remix never
 * lends its canvas to the original.
 */
export async function fetchCanvas(track: { videoId: string; title: string; artist: string; durationSec: number }): Promise<string | null> {
  if (canvases.has(track.videoId)) return canvases.get(track.videoId)!;
  const hits = await bridge().call('spotify.searchTracks', { query: `${track.title} ${track.artist}` }, { timeoutMs: 20_000 });
  const pick = best(
    hits.map((h) => ({
      title: h.title,
      artist: h.artist,
      durationSec: h.durationMs > 0 ? Math.round(h.durationMs / 1000) : null,
      album: h.album,
      id: h.id,
    })),
    { title: track.title, artist: track.artist, durationSec: track.durationSec > 0 ? Math.round(track.durationSec) : null },
  );
  const url = pick ? (await bridge().call('spotify.canvas', { trackId: pick.id }, { timeoutMs: 20_000 })).url : null;
  canvases.set(track.videoId, url);
  if (canvases.size > 200) canvases.delete(canvases.keys().next().value!);
  return url;
}
