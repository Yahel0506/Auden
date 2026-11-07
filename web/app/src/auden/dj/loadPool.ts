import { hasOfflineCopy } from '../offline/kept';
import { hasLink } from '../library/linkIndex';
import { localArtUrls } from '../library/ui/data';
import { openAudenDb } from '../db/schema';
import { listPlaylists, entriesOf } from '../playlists/store';
import { listeningMonths } from '../stats/listening';
import { type Pool, buildPool } from './pool';
import { youtubeStreamable } from './ready';

/** The DJ's pool from this device: the library, the listening history and the playlists, only what can play right now. */
export async function loadDjPool(): Promise<Pool> {
  const art = await localArtUrls();
  const playlists = await listPlaylists();
  const entries = await Promise.all(playlists.map(async (s) => ({ title: s.playlist.title, rowIds: (await entriesOf(s.playlist.id)).flatMap((i) => (i.row?.id != null ? [i.row.id] : [])) })));
  return buildPool(
    {
      months: listeningMonths,
      rows: async () => (await openAudenDb()).getAll('track'),
      playlists: async () => entries,
      art,
      hasFile: (track) => hasLink(track) || hasOfflineCopy(track.videoId),
    },
    { now: Date.now(), online: navigator.onLine && youtubeStreamable() },
  );
}
