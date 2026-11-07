import { create } from 'zustand';
import type { Track } from '@/data/music/types';
import { best, type MatchCandidate } from '@/data/match/trackMatcher';
import { tracks } from '../../db/dao';
import type { TrackRow } from '../../db/schema';
import { parseDurationText } from '../identity';
import { UNKNOWN_ARTIST } from '../scan/rows';
import { extractYoutubeId } from '../youtubeIdInName';

/**
 * Finds the YouTube Music song behind an indexed file (Android's TrackIdentifier): a video id in the file
 * name settles it; otherwise a YouTube Music search judged by the shared matcher. A certain match links the
 * file to the song; a doubtful one is kept for the review sheet; nothing found is remembered so it isn't
 * searched again until the user retries.
 */

export const IdentStatus = { MATCHED: 'MATCHED', REVIEW: 'REVIEW', NOT_FOUND: 'NOT_FOUND', REJECTED: 'REJECTED' } as const;
export const IdentSource = { FILE_NAME: 'FILE_NAME', SEARCH: 'SEARCH', MANUAL: 'MANUAL' } as const;

export type SongSearch = (query: string) => Promise<Track[]>;

const candidateOf = (t: Track): MatchCandidate & { track: Track } => ({
  title: t.title,
  artist: t.artists.map((a) => a.name).join(', '),
  durationSec: t.durationSec,
  album: t.album?.name ?? null,
  explicit: t.explicit,
  track: t,
});

const artistKnown = (artist: string): boolean => artist.trim() !== '' && artist !== UNKNOWN_ARTIST;

/** The patch for one row. Pure apart from the injected search. Null leaves the row as it is (nothing to say yet). */
export async function identifyRow(row: TrackRow, search: SongSearch, now = Date.now()): Promise<Partial<TrackRow>> {
  const fileName = (row.path ?? '').slice((row.path ?? '').lastIndexOf('/') + 1);
  const fromName = extractYoutubeId(fileName);
  if (fromName) return { videoId: fromName, identStatus: IdentStatus.MATCHED, identSource: IdentSource.FILE_NAME, identifiedAt: now };

  const known = artistKnown(row.artist);
  const target = {
    title: row.title,
    artist: known ? row.artist : '',
    durationSec: row.durationMs ? Math.round(row.durationMs / 1000) : parseDurationText(row.durationText),
    album: row.album,
  };
  const queries = [...new Set([[row.title, known ? row.artist : ''].filter(Boolean).join(' '), row.title])].slice(0, 2);
  const seen = new Map<string, MatchCandidate & { track: Track }>();
  for (const [i, query] of queries.entries()) {
    for (const t of await search(query)) if (!seen.has(t.videoId)) seen.set(t.videoId, candidateOf(t));
    // Android stops after the first query once it has a certain answer.
    if (i === 0 && known && best([...seen.values()], target)) break;
  }
  const list = [...seen.values()];
  const certain = known ? best(list, target) : null;
  if (certain) {
    return {
      videoId: certain.track.videoId,
      identStatus: IdentStatus.MATCHED,
      identSource: IdentSource.SEARCH,
      identifiedAt: now,
      albumId: row.albumId ?? certain.track.album?.id ?? null,
      artistId: row.artistId ?? certain.track.artists[0]?.id ?? null,
    };
  }
  const guess = list.length ? (best(list, { ...target, artist: '' }) ?? list[0]!) : null;
  if (guess) {
    return {
      identStatus: IdentStatus.REVIEW,
      suggestedVideoId: guess.track.videoId,
      suggestedTitle: guess.track.title,
      suggestedArtist: guess.artist,
      identifiedAt: now,
    };
  }
  return { identStatus: IdentStatus.NOT_FOUND, identifiedAt: now };
}

/** Rows still to identify: a file, no YouTube id, never judged. */
export const needsIdentifying = (r: TrackRow): boolean => !!r.localUri && !r.videoId && !r.identStatus;

export interface IdentifyState {
  running: boolean;
  done: number;
  total: number;
  error: string | null;
}
export const useIdentify = create<IdentifyState>(() => ({ running: false, done: 0, total: 0, error: null }));

let cancelled = false;
let current: Promise<void> | null = null;
export const cancelIdentify = (): void => {
  cancelled = true;
};

const CONCURRENCY = 3;

/**
 * Identifies every pending file, three at a time. Resumable (judged rows are saved as they finish and are
 * skipped next time) and cancellable; a failing search backs off and stops the run instead of marking every
 * file "not found".
 */
export function startIdentify(search: SongSearch, opts: { retryMisses?: boolean } = {}): Promise<void> {
  if (current) return current;
  current = run(search, opts).finally(() => {
    current = null;
  });
  return current;
}

async function run(search: SongSearch, opts: { retryMisses?: boolean }): Promise<void> {
  cancelled = false;
  if (opts.retryMisses) {
    for (const r of await tracks.all()) {
      if (r.localUri && !r.videoId && (r.identStatus === IdentStatus.NOT_FOUND || r.identStatus === IdentStatus.REJECTED)) {
        await tracks.put({ ...r, id: r.id!, identStatus: null, identSource: null, identifiedAt: null });
      }
    }
  }
  const pending = (await tracks.all()).filter(needsIdentifying);
  useIdentify.setState({ running: pending.length > 0, done: 0, total: pending.length, error: null });
  let next = 0;
  let failures = 0;
  const retry: TrackRow[] = [];
  const worker = async () => {
    while (!cancelled && failures < 3) {
      const row = retry.shift() ?? pending[next++];
      if (!row) return;
      try {
        const patch = await identifyRow(row, search);
        const fresh = await tracks.get(row.id!);
        if (fresh && !fresh.videoId) await tracks.put({ ...fresh, ...patch, id: row.id! });
        failures = 0;
        if (useIdentify.getState().error) useIdentify.setState({ error: null });
      } catch (error) {
        failures++;
        useIdentify.setState({ error: error instanceof Error ? error.message : String(error) });
        await new Promise((r) => setTimeout(r, 1500 * failures));
        retry.push(row); // another turn, not skipped
        continue;
      }
      useIdentify.setState((s) => ({ done: s.done + 1 }));
    }
  };
  await Promise.all(Array.from({ length: CONCURRENCY }, worker));
  useIdentify.setState({ running: false });
}

/** Review sheet actions. */
export async function acceptSuggestion(id: number): Promise<void> {
  const r = await tracks.get(id);
  if (!r?.suggestedVideoId) return;
  await tracks.put({
    ...r, id,
    videoId: r.suggestedVideoId, identStatus: IdentStatus.MATCHED, identSource: IdentSource.MANUAL, identifiedAt: Date.now(),
    suggestedVideoId: null, suggestedTitle: null, suggestedArtist: null,
  });
}
export async function rejectSuggestion(id: number): Promise<void> {
  const r = await tracks.get(id);
  if (!r) return;
  await tracks.put({
    ...r, id,
    identStatus: IdentStatus.REJECTED, identifiedAt: Date.now(), suggestedVideoId: null, suggestedTitle: null, suggestedArtist: null,
  });
}
