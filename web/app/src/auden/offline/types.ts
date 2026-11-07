/**
 * "Save for offline playback" (docs/AUDEN_WEB_PLAN.md, Phase 12): what is asked for, what is kept.
 *
 * The record is the one Android's QueueStore keeps (enough of the song to ask for it again) plus what the
 * browser needs: a persisted retry time, and for a finished copy the file's name in OPFS and a small cover,
 * so the Downloaded list has art with no network.
 */

/** What identifies and describes a song, as much as the manager needs to list and group it. */
export interface OfflineSong {
  videoId: string;
  title: string;
  artist: string;
  album: string | null;
  albumId: string | null;
  artistId: string | null;
  thumbnailUrl: string | null;
  durationSec: number | null;
  /** The album track number, when the list it came from had one (for the folder layout's `{track}`). */
  trackNumber?: number | null;
  /** The album's artist (the first credited), so a featured song stays in its album's folder. */
  albumArtist?: string | null;
}

/** Waiting (queued or backing off) or stopped with a reason. A running job is runtime-only: after a restart it is queued again. */
export interface OfflineJob extends OfflineSong {
  state: 'queued' | 'failed';
  /** Retries already spent on reasons worth retrying. */
  attempt: number;
  /** Not before this time (ms since the epoch): the back-off survives a reload. */
  notBefore: number;
  /** Why it failed, or why it is waiting to be retried. */
  reason: string | null;
  addedAt: number;
  /** Also write it into the chosen folder once it is kept (Phase 12b). */
  toFolder?: boolean;
}

/** Where a copy was written in the chosen folder. */
export interface FolderSaved {
  /** Below the folder, `/`-separated, extension included. */
  path: string;
  savedAt: number;
  bytes: number;
  /** A `.lrc` was written beside it. */
  lrc: boolean;
}

export interface OfflineItem extends OfflineSong {
  /** The file's name inside the offline folder. */
  file: string;
  mime: string;
  bytes: number;
  itag: number;
  savedAt: number;
  /** A small cover (best effort: a thumbnail the browser may refuse to read), for views with no network. */
  cover: Blob | null;
  /** Waiting to be written to the folder (asked for, not yet done: permission, or lyrics still being found). */
  folderPending?: boolean;
  folder?: FolderSaved;
}

/** Why the whole queue is stopped. `user` = paused by the listener. */
export type PauseReason = 'user' | 'offline' | 'storage-full' | 'verify';

export const PAUSE_TEXT: Record<PauseReason, string> = {
  user: 'Paused.',
  offline: 'Waiting for a connection.',
  'storage-full': 'This browser has no room left for more songs. Free some space and resume.',
  verify: 'YouTube is asking to confirm you’re not a bot. Sign in to YouTube Music in this browser, then resume.',
};

export interface PausedState {
  reason: PauseReason;
}

/** A playlist saved whole for offline listening (or into the folder), watched for new songs (keepUpdated.ts). */
export interface Followed {
  /** `local`: a playlist on this device (a saved copy or one made here); `yt`: a YouTube Music playlist. */
  kind: 'local' | 'yt';
  id: string;
  title: string;
  /** The songs already dealt with: queued, or kept. A song the listener later removes from offline stays here, so it does not come back. */
  known: string[];
  /** Also write what it fetches into the chosen folder. */
  toFolder: boolean;
  /** More new songs than may be fetched unasked: waiting for the listener's yes (ids of the songs). */
  ask?: { ids: string[]; at: number };
}

/** Keyed by `kind:id`. */
export type FollowedMap = Record<string, Followed>;
export const followedKey = (kind: Followed['kind'], id: string): string => `${kind}:${id}`;
