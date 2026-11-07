// Port of dev/auden/library/io/PlaylistMatcher.kt.

import { normalizeKey } from '../library/identity';
import { isUrl, type PlaylistEntry, Paths } from './files';

/** The part of a library track the matcher needs. */
export interface MatchableTrack {
  id: number;
  videoId: string | null;
  path: string | null;
  title: string;
  artist: string;
  durationSec: number | null;
}

interface PathCandidate {
  track: MatchableTrack;
  lowerPath: string;
}

/**
 * Finds the library track a playlist line means.
 *
 * Tried from most to least certain, stopping at the first hit:
 *  1. the video id, for files Auden wrote itself;
 *  2. the exact path;
 *  3. the same file name somewhere else (a playlist moved between devices),
 *     choosing the candidate whose path shares the longest tail with the line;
 *  4. title and artist (and length, when both sides know it) after normalising.
 */
export class PlaylistMatcher {
  private readonly byVideoId = new Map<string, MatchableTrack>();
  private readonly byPath = new Map<string, MatchableTrack>();
  private readonly byFileName = new Map<string, PathCandidate[]>();
  private readonly byTitle = new Map<string, MatchableTrack[]>();

  constructor(tracks: MatchableTrack[]) {
    for (const track of tracks) {
      // Later tracks win on duplicate keys, as Kotlin's associateBy does.
      if (track.videoId != null && track.videoId.trim() !== '') this.byVideoId.set(track.videoId, track);
      if (track.path != null) {
        const lowerPath = track.path.toLowerCase();
        this.byPath.set(lowerPath, track);
        push(this.byFileName, Paths.fileName(track.path).toLowerCase(), { track, lowerPath });
      }
      push(this.byTitle, normalizeKey(track.title), track);
    }
  }

  match(entry: PlaylistEntry): MatchableTrack | null {
    if (entry.videoId != null) {
      const byId = this.byVideoId.get(entry.videoId);
      if (byId) return byId;
    }

    if (entry.path != null && !isUrl(entry.path)) {
      const lowerPath = entry.path.toLowerCase();
      const exact = this.byPath.get(lowerPath);
      if (exact) return exact;
      const candidates = this.byFileName.get(Paths.fileName(entry.path).toLowerCase());
      if (candidates) return closestByTail(candidates, lowerPath);
    }

    const title = entry.title ? normalizeKey(entry.title) : '';
    if (title === '') return null;
    const artist = entry.artist != null ? normalizeKey(entry.artist) : null;
    const candidates = this.byTitle.get(title) ?? [];
    return (
      candidates.find(
        (track) =>
          (artist == null || artistAgrees(normalizeKey(track.artist), artist)) && lengthAgrees(track, entry),
      ) ?? null
    );
  }
}

function push<K, V>(map: Map<K, V[]>, key: K, value: V): void {
  const list = map.get(key);
  if (list) list.push(value);
  else map.set(key, [value]);
}

/** The candidate sharing the longest tail with [lowerPath]; the first one wins a tie. */
function closestByTail(candidates: PathCandidate[], lowerPath: string): MatchableTrack | null {
  let best: MatchableTrack | null = null;
  let bestScore = -1;
  for (const candidate of candidates) {
    const score = commonTail(candidate.lowerPath, lowerPath);
    if (score > bestScore) {
      best = candidate.track;
      bestScore = score;
    }
  }
  return best;
}

function artistAgrees(trackArtist: string, artist: string): boolean {
  return trackArtist === artist || trackArtist.includes(artist) || artist.includes(trackArtist);
}

function lengthAgrees(track: MatchableTrack, entry: PlaylistEntry): boolean {
  const want = entry.durationSec;
  if (want == null) return true;
  const have = track.durationSec;
  if (have == null) return true;
  return Math.abs(want - have) <= 2.0;
}

/** How many trailing characters two paths share. */
function commonTail(a: string, b: string): number {
  let i = a.length;
  let j = b.length;
  while (i > 0 && j > 0 && a[i - 1] === b[j - 1]) {
    i--;
    j--;
  }
  return a.length - i;
}
