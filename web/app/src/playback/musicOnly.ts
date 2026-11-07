import { create } from 'zustand';
import { persist } from 'zustand/middleware';
import type { Track } from '@/data/music/types';
import { isSyntheticId, sourceOf } from './source';
import { trackLog } from './trackLog';

/**
 * "Prefer music-only version" (the phone's MusicOnlyPlayback.kt): a music
 * video is heard as its catalogue song, and never starts as the video first.
 *
 * The player (state/player.ts) converts the playing row before it loads and
 * the next one in the background, so a crossfade or an Automix blend into the
 * next track is planned against the song rather than found to be a video when
 * it arrives. A converted row is an ordinary song row in the same queue entry
 * (same entry id, tier and source): nothing else needs to know.
 *
 * A row with no audio version, or none in [ANSWER_TIMEOUT_MS], is let go
 * ([settled]) and plays the video's own audio, without the picture. Never in
 * a party, where everyone hears the one cut.
 */

interface MusicOnlyState {
  preferMusicOnly: boolean;
  setPreferMusicOnly: (on: boolean) => void;
}

/** Settings › Prefer music-only version. Off by default, as on the phone. */
export const useMusicOnly = create<MusicOnlyState>()(
  persist(
    (set) => ({
      preferMusicOnly: false,
      setPreferMusicOnly: (preferMusicOnly) => set({ preferMusicOnly }),
    }),
    { name: 'auden:musiconly', version: 1, partialize: (s) => ({ preferMusicOnly: s.preferMusicOnly }) },
  ),
);

/** How long a row waits for its audio version before playing the video's own audio. */
export const ANSWER_TIMEOUT_MS = 15_000;

/** Each video's song, or null: it has none (or is itself the song). Answers survive a flip of the setting. */
const answers = new Map<string, Track | null>();
/** Lookups in flight, shared by the playing row's wait and the next row's background conversion. */
const lookups = new Map<string, Promise<Track | null | undefined>>();
/** Videos playing as themselves for now: the lookup failed or was too slow. */
const settled = new Set<string>();
/** Videos the listener switched to by hand, which stay videos (no web switch calls this yet). */
const kept = new Set<string>();
/** Which cut a video id is, where a radio said so (Candidate.isVideo). */
const cuts = new Map<string, boolean>();
/** Waits to let go of when the setting changes: nothing decided under the old value stands. */
const waiters = new Set<() => void>();

/** Never in a party, where everyone hears the one cut. */
export function musicOnlyActive(inParty: boolean): boolean {
  return useMusicOnly.getState().preferMusicOnly && !inParty;
}

/** Whether [videoId] shows its picture rather than its artwork. */
export function showsPicture(videoId: string | null | undefined, inParty: boolean): boolean {
  return !musicOnlyActive(inParty) || (!!videoId && kept.has(videoId));
}

/** What the player knows about a row that the track alone doesn't say. */
export interface RowFacts {
  inParty: boolean;
  /** A row the DJ queued (its lines name the song they introduce). */
  dj: boolean;
  /** A linked file or an offline copy: what's on the device is what plays. */
  playsLocally: boolean;
}

/**
 * Whether [track] should be turned into its song: the setting is on, it's a
 * YouTube row that looks like a music video (a radio said so, else it has no
 * album), and it's still owed its answer.
 */
export function wantsMusicOnly(track: Track, facts: RowFacts): boolean {
  if (!musicOnlyActive(facts.inParty) || facts.dj || facts.playsLocally) return false;
  if (sourceOf(track) !== 'youtube' || track.unavailable || isSyntheticId(track.videoId)) return false;
  const id = track.videoId;
  if (kept.has(id) || settled.has(id) || answers.get(id) === null) return false;
  return cuts.get(id) ?? track.album === null;
}

/**
 * [audio] standing in [video]'s place. The queue entry (id, tier, source) is
 * kept by the caller; only what the Spotify list said this row stands for
 * comes across from the video, since it's the same song.
 */
export function asMusicOnlyOf(audio: Track, video: Track): Track {
  const song: Track = { ...audio };
  delete song.spotifyId;
  if (video.spotifyId) song.spotifyId = video.spotifyId;
  return song;
}

/** Looks up a video's song; the player's tests replace it through the api module. */
async function lookUpAudioVersion(videoId: string): Promise<Track | null> {
  const { audioVersionOf } = await import('@/data/music/api');
  return audioVersionOf(videoId);
}

/** The lookup for [videoId], started once: a Track or null is an answer, undefined a failure. */
function lookup(videoId: string): Promise<Track | null | undefined> {
  let running = lookups.get(videoId);
  if (!running) {
    running = lookUpAudioVersion(videoId)
      .then(
        (audio) => {
          const answer = audio && audio.videoId !== videoId ? audio : null;
          answers.set(videoId, answer);
          return answer;
        },
        (error: unknown) => {
          trackLog.w(`music only: couldn't look up the audio version (${error instanceof Error ? error.message : String(error)})`, videoId);
          return undefined;
        },
      )
      .finally(() => lookups.delete(videoId));
    lookups.set(videoId, running);
  }
  return running;
}

/**
 * The song to play in [video]'s place, or null to play it as itself. Waits at
 * most [timeoutMs]; a failed or slow lookup settles the video until it is
 * asked about again (see [forgetExcept]). A change of the setting lets the
 * wait go at once, unsettled.
 */
export async function musicOnlyVersion(video: Track, timeoutMs = ANSWER_TIMEOUT_MS): Promise<Track | null> {
  const id = video.videoId;
  const known = answers.get(id);
  if (known !== undefined) return known && asMusicOnlyOf(known, video);
  let timer: ReturnType<typeof setTimeout> | undefined;
  let release: (() => void) | undefined;
  const outcome = await Promise.race([
    lookup(id).then((answer) => ({ kind: 'answer' as const, answer })),
    new Promise<{ kind: 'timeout' }>((resolve) => {
      timer = setTimeout(() => resolve({ kind: 'timeout' }), timeoutMs);
    }),
    new Promise<{ kind: 'released' }>((resolve) => {
      release = () => resolve({ kind: 'released' });
      waiters.add(release);
    }),
  ]);
  clearTimeout(timer);
  if (release) waiters.delete(release);
  if (outcome.kind === 'released') return null;
  if (outcome.kind === 'timeout' || outcome.answer === undefined) {
    trackLog.i(
      outcome.kind === 'timeout' ? "music only: no answer in time; playing the video's own audio" : "music only: playing the video's own audio",
      id,
    );
    settled.add(id);
    return null;
  }
  if (!outcome.answer) {
    trackLog.d("music only: no audio version; playing the video's own audio", id);
    return null;
  }
  return asMusicOnlyOf(outcome.answer, video);
}

/** Remembers which cut each radio row is, so a song from a radio is never looked up. */
export function noteCuts(candidates: readonly { track: Pick<Track, 'videoId'>; isVideo: boolean }[]): void {
  for (const c of candidates) cuts.set(c.track.videoId, c.isVideo);
}

/** A later play asks again, as a failed lookup may only have been the network. */
export function forgetExcept(videoIds: ReadonlySet<string>): void {
  for (const id of settled) if (!videoIds.has(id)) settled.delete(id);
}

/** The listener switched to [videoId] by hand. */
export function keep(videoId: string): void {
  kept.add(videoId);
}

/** The listener switched away from [videoId] by hand. */
export function letGo(videoId: string): void {
  kept.delete(videoId);
}

/** The setting changed, so nothing decided under the old value stands. */
export function resetMusicOnly(): void {
  kept.clear();
  settled.clear();
  for (const release of [...waiters]) release();
  waiters.clear();
}

useMusicOnly.subscribe((state, previous) => {
  if (state.preferMusicOnly !== previous.preferMusicOnly) resetMusicOnly();
});

/** For tests: forgets every answer and lookup too. */
export function clearMusicOnlyForTests(): void {
  resetMusicOnly();
  answers.clear();
  lookups.clear();
  cuts.clear();
}
