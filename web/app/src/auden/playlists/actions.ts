import { searchSongs } from '@/data/music/api';
import { best } from '@/data/match/trackMatcher';
import type { Track } from '@/data/music/types';
import { useJobs } from '../jobs/jobs';
import { cloudDeps } from './cloudDeps';
import { type SaveOutcome, resaveCopy, saveSpotifyPlaylist, saveYouTubeList } from './cloudSaver';
import { refreshCoverFor } from './covers';
import type { PlaylistEntry } from './files';
import { addTracks, createPlaylist, deletePlaylist, duplicatePlaylist, moveEntry, removeEntry, renamePlaylist } from './store';
import { describeImport, exportPlaylist, importPlaylistFile, type ExportFormat, type OnlineSearch } from './transfer';
import { askName } from './ui/askName';

/** The UI's entry points: each does the store call, then refreshes the mosaic (once, off the critical path). */

export async function newPlaylist(tracks: Array<Track | number> = []): Promise<string | null> {
  const name = await askName('New Playlist', '', 'Create');
  if (!name) return null;
  const { id } = await createPlaylist(name, tracks);
  void refreshCoverFor(id);
  return id;
}

export async function addToPlaylist(playlistId: string, tracks: Track[]) {
  const result = await addTracks(playlistId, tracks);
  if (result.added) void refreshCoverFor(playlistId);
  return result;
}

export async function removeFromPlaylist(playlistId: string, entryId: number) {
  await removeEntry(entryId);
  void refreshCoverFor(playlistId);
}

export async function moveInPlaylist(playlistId: string, entryId: number, beforeEntryId: number | null): Promise<boolean> {
  const moved = await moveEntry(playlistId, entryId, beforeEntryId);
  if (moved) void refreshCoverFor(playlistId); // the first four songs may have changed
  return moved;
}

export async function renameWithPrompt(playlistId: string, current: string): Promise<void> {
  const name = await askName('Rename Playlist', current);
  if (name && name !== current) await renamePlaylist(playlistId, name);
}

export async function duplicate(playlistId: string): Promise<string | null> {
  const id = await duplicatePlaylist(playlistId);
  if (id) void refreshCoverFor(id);
  return id;
}

export const removePlaylist = deletePlaylist;

/** Hands the playlist to the browser as a file. */
export async function downloadPlaylist(playlistId: string, format: ExportFormat): Promise<void> {
  const file = await exportPlaylist(playlistId, format);
  if (!file) return;
  const url = URL.createObjectURL(new Blob([file.text], { type: `${file.mime};charset=utf-8` }));
  const a = document.createElement('a');
  a.href = url;
  a.download = file.fileName;
  document.body.append(a);
  a.click();
  a.remove();
  setTimeout(() => URL.revokeObjectURL(url), 10_000);
}

/** For lines a file names but the library doesn't have: a YouTube Music search judged by the shared matcher. */
export const searchOnline: OnlineSearch = async (entry: PlaylistEntry) => {
  const title = entry.title?.trim();
  if (!title) return null;
  const found = await searchSongs([title, entry.artist].filter(Boolean).join(' '));
  const candidates = found.map((t) => ({ title: t.title, artist: t.artists.map((a) => a.name).join(', '), durationSec: t.durationSec, album: t.album?.name ?? null, explicit: t.explicit, track: t }));
  return best(candidates, { title, artist: entry.artist ?? '', durationSec: entry.durationSec ?? null, album: entry.album ?? null })?.track ?? null;
};

const controllers = new Map<string, AbortController>();

/** Imports dropped or picked playlist files, one summary per file on the job banner. Returns the last playlist made. */
export async function importFiles(files: File[], opts: { online?: boolean } = {}): Promise<string | null> {
  let last: string | null = null;
  for (const file of files) {
    const jobId = `import:${file.name}:${file.lastModified}`;
    useJobs.getState().start({ id: jobId, title: `Importing ${file.name}`, total: 1, cancel: () => controllers.get(jobId)?.abort() });
    const controller = new AbortController();
    controllers.set(jobId, controller);
    try {
      const summary = await importPlaylistFile(file, {
        ...(opts.online ? { search: searchOnline } : {}),
        signal: controller.signal,
        onProgress: (done, total) => useJobs.getState().update(jobId, { done, total, detail: `${done} of ${total}` }),
      });
      void refreshCoverFor(summary.playlistId);
      last = summary.playlistId;
      useJobs.getState().finish(jobId, describeImport(summary));
    } catch (error) {
      useJobs.getState().finish(jobId, error instanceof Error ? error.message : String(error));
    } finally {
      controllers.delete(jobId);
    }
  }
  return last;
}

function describeSave(outcome: SaveOutcome, what: string): string {
  if ('failed' in outcome) return outcome.failed;
  const tail = outcome.unmatched ? ` (${outcome.unmatched} not found on YouTube Music)` : '';
  return outcome.created ? `Saved “${outcome.name}” to this device${tail}.` : `“${what}” is up to date: ${outcome.added} new, ${outcome.removed} removed${tail}.`;
}

/** Saves a YouTube Music playlist or album as a copy on this device. */
export async function saveYouTubeToDevice(browseId: string, title: string, thumbnail: string | null): Promise<SaveOutcome> {
  const jobId = `save:${browseId}`;
  useJobs.getState().start({ id: jobId, title: `Saving “${title}”`, total: 1 });
  const outcome = await saveYouTubeList(cloudDeps, browseId, { title, thumbnail });
  if (!('failed' in outcome)) void refreshCoverFor(outcome.playlistId);
  useJobs.getState().finish(jobId, describeSave(outcome, title));
  return outcome;
}

/** Saves a Spotify playlist as a copy on this device (every song looked up on YouTube Music). */
export async function saveSpotifyToDevice(playlistId: string, title: string, cover: string | null): Promise<SaveOutcome> {
  const jobId = `save:spotify:${playlistId}`;
  useJobs.getState().start({ id: jobId, title: `Saving “${title}”`, total: 1 });
  const outcome = await saveSpotifyPlaylist(cloudDeps, playlistId, { title, cover });
  if (!('failed' in outcome)) void refreshCoverFor(outcome.playlistId);
  useJobs.getState().finish(jobId, describeSave(outcome, title));
  return outcome;
}

export { resaveCopy };
