/**
 * What a re-save ("sync") of a cloud playlist changes in its device copy. Pure: the store gathers the facts in its
 * transaction and applies the answer in the same one.
 *
 * The rule (CloudSaver.kt, tightened):
 * - Only an entry the sync itself put there (it carries `fromSpotifyId`) may be removed, and only when the Spotify
 *   original no longer lists that id. A hand-added entry is never removed, whatever track row it shares.
 * - Nothing is removed unless the read of the original was complete and at least one of its tracks matched: a short
 *   page, a failed read or a matcher that found nothing must not empty a playlist.
 * - A YouTube copy (`sourceIds: null`) only gains songs.
 * - A song the copy already holds (same row, same song, or an entry already made for the same Spotify id) is not
 *   added again; two Spotify ids that match one video add one entry.
 */

export interface ReconcileEntry {
  entryId: number;
  trackId: number;
  fromSpotifyId?: string;
}

export interface ReconcileMatch {
  /** The Spotify id this track was matched for (Spotify copies only). */
  spotifyId?: string;
  /** Identity of the matched song within this run: equal keys are the same song. */
  trackKey: string;
}

export interface ReconcileInput<M extends ReconcileMatch> {
  existing: readonly ReconcileEntry[];
  /** Every id the original lists right now, matched or not. Null for a YouTube copy: add-only. */
  sourceIds: readonly string[] | null;
  /** The original was read to the end (every page). A partial read never removes. */
  complete: boolean;
  matched: readonly M[];
  /** The existing entry that already holds the song behind `trackKey` (same row, or the same song), if any. */
  holderOf: (trackKey: string) => number | undefined;
}

export interface ReconcileResult<M extends ReconcileMatch> {
  /** Entry ids to delete. */
  remove: number[];
  /** Matches to append, in order. */
  add: M[];
  /**
   * Entries that came from a Spotify id the original dropped, but whose song is still there under another id (two
   * ids matched one video): kept, and re-attributed to the id that still lists them.
   */
  retag: Array<{ entryId: number; fromSpotifyId: string }>;
}

export function reconcile<M extends ReconcileMatch>(input: ReconcileInput<M>): ReconcileResult<M> {
  const { existing, complete, matched, holderOf } = input;
  // A matched id is listed by definition, whatever the caller passed.
  const listed = input.sourceIds ? new Set<string>([...input.sourceIds, ...matched.flatMap((m) => (m.spotifyId ? [m.spotifyId] : []))]) : null;
  const mayRemove = listed !== null && complete && matched.length > 0;

  const doomed = new Set<number>();
  if (mayRemove) {
    for (const e of existing) if (e.fromSpotifyId && !listed.has(e.fromSpotifyId)) doomed.add(e.entryId);
  }
  const bySpotify = new Map<string, number>();
  for (const e of existing) if (e.fromSpotifyId && !doomed.has(e.entryId) && !bySpotify.has(e.fromSpotifyId)) bySpotify.set(e.fromSpotifyId, e.entryId);

  const retag = new Map<number, string>();
  const add: M[] = [];
  const seenKeys = new Set<string>();
  const seenSpotify = new Set<string>();
  for (const m of matched) {
    // Already here for this Spotify id (perhaps matched to another video last time): one entry per id.
    if (m.spotifyId && (bySpotify.has(m.spotifyId) || seenSpotify.has(m.spotifyId))) continue;
    const holder = holderOf(m.trackKey);
    if (holder !== undefined) {
      if (doomed.has(holder) && m.spotifyId) {
        doomed.delete(holder);
        retag.set(holder, m.spotifyId);
        bySpotify.set(m.spotifyId, holder);
      }
      continue;
    }
    if (seenKeys.has(m.trackKey)) continue;
    seenKeys.add(m.trackKey);
    if (m.spotifyId) seenSpotify.add(m.spotifyId);
    add.push(m);
  }
  return {
    remove: existing.filter((e) => doomed.has(e.entryId)).map((e) => e.entryId),
    add,
    retag: [...retag].map(([entryId, fromSpotifyId]) => ({ entryId, fromSpotifyId })),
  };
}
