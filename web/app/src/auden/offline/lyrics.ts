import { enabledSources } from '@/lyrics/settings';
import { lookupLyrics } from '@/lyrics/repository';
import { runBatch, saveBest, type BatchSong } from '../lyrics/batch';
import type { OfflineItem } from './types';

/**
 * A finished download gets its lyrics saved through the Phase 5 store, quietly: the songs that finish within a
 * few seconds of one another are looked up as one batch with no banner (the lyrics batch's own banner takes over
 * the screen and refuses to start while another run is going, which a download queue would trip on).
 */
const WAIT_MS = 4_000;
let pending: BatchSong[] = [];
let timer: ReturnType<typeof setTimeout> | undefined;
let running: Promise<unknown> = Promise.resolve();
let afterBatch: ((videoIds: string[]) => void) | null = null;

/** Called with the songs of each finished batch (the folder's `.lrc` is written then, once lyrics exist). */
export function onLyricsBatchDone(callback: ((videoIds: string[]) => void) | null): void {
  afterBatch = callback;
}

export function saveLyricsFor(item: OfflineItem): void {
  pending.push({ videoId: item.videoId, title: item.title, artist: item.artist, durationSec: item.durationSec, album: item.album, source: 'youtube' });
  clearTimeout(timer);
  timer = setTimeout(() => {
    const songs = pending;
    pending = [];
    running = running.then(() =>
      runBatch(songs, { lookup: lookupLyrics, save: saveBest, sequence: enabledSources() })
        .catch(() => undefined)
        .then(() => afterBatch?.(songs.map((s) => s.videoId))),
    );
  }, WAIT_MS);
}
