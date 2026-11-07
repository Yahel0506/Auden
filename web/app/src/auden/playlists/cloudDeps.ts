import { fetchAlbum, fetchPlaylist } from '@/data/music/api';
import { fetchSpotifyPlaylistInfo, fetchSpotifyTracks } from '@/data/spotify/api';
import { resolveSpotifyTrack } from '@/data/spotify/matches';
import type { Track } from '@/data/music/types';
import type { CloudDeps } from './cloudSaver';

type SpotifyTrackLike = Awaited<ReturnType<typeof fetchSpotifyTracks>>[number];

/** The real readers: YouTube Music through the in-page client, Spotify through the extension. Read-only. */
export const cloudDeps: CloudDeps<SpotifyTrackLike> = {
  async youtube(browseId) {
    if (/^(MPREb|OLAK)/.test(browseId.replace(/^VL/, ''))) {
      const album = await fetchAlbum(browseId.replace(/^VL/, ''));
      return { title: album.title, thumbnail: album.thumbnail, tracks: album.tracks };
    }
    const { page, more } = await fetchPlaylist(browseId);
    const tracks: Track[] = [...page.tracks];
    // A page limit: a continuation that never ends must not leave "Saving…" spinning forever.
    for (let next = more, pages = 0; next && pages < 500; pages++) {
      const paged = await next();
      tracks.push(...paged.data);
      next = paged.next;
    }
    return { title: page.title, thumbnail: page.thumbnail, tracks };
  },
  spotify: {
    async tracks(playlistId) {
      let reached = 0;
      let total = Infinity;
      const items = await fetchSpotifyTracks(playlistId, (_soFar, listed, nextOffset) => {
        total = listed;
        reached = nextOffset;
      });
      // A read that stopped short (a page came back empty before the end) is not a complete one.
      const complete = reached >= total;
      const info = await fetchSpotifyPlaylistInfo(playlistId).catch(() => null);
      return { items, complete, cover: (info as { imageUrl?: string | null } | null)?.imageUrl ?? null };
    },
    // The match store remembers finds across sessions. A failed search is "not matched" this time, never "removed".
    match: async (item) => {
      const result = await resolveSpotifyTrack(item);
      return result.status === 'found' ? result.track : null;
    },
  },
};
