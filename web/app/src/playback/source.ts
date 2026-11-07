import type { Track } from '@/data/music/types';

/**
 * Where a track's audio comes from (docs/AUDEN_WEB_PLAN.md, Phase 1), and
 * which medium plays it.
 *
 * Identity and medium are two different questions. A track's *source* is
 * what it is: a YouTube song (a real videoId), a local file with no YouTube
 * counterpart, or a clip made in the app (the DJ's voice). Its *medium* is
 * what plays it this time: a YouTube song with a local file linked to it is
 * still a YouTube song (shared in a party, reported to YouTube, its lyrics
 * looked up by videoId), it just plays from the file when the file is there.
 */

export type TrackSource = NonNullable<Track['source']>;

/** Synthetic ids: never a videoId, never sent to YouTube. */
export const LOCAL_ID_PREFIX = 'auden:local:';
export const CLIP_ID_PREFIX = 'auden:clip:';

export function sourceOf(track: Pick<Track, 'source'>): TrackSource {
  return track.source ?? 'youtube';
}

/** Whether a party may carry it, and YouTube may hear about it: only a YouTube song. */
export function isShareable(track: Pick<Track, 'source'>): boolean {
  return sourceOf(track) === 'youtube';
}

export const localTrackId = (rowId: string | number): string => `${LOCAL_ID_PREFIX}${rowId}`;
export const clipTrackId = (clipId: string): string => `${CLIP_ID_PREFIX}${clipId}`;

/** The local library row behind an `auden:local:` id, or null for any other id. */
export function localRowIdOf(trackId: string): string | null {
  return trackId.startsWith(LOCAL_ID_PREFIX) ? trackId.slice(LOCAL_ID_PREFIX.length) || null : null;
}

/** The clip behind an `auden:clip:` id, or null for any other id. */
export function clipIdOf(trackId: string): string | null {
  return trackId.startsWith(CLIP_ID_PREFIX) ? trackId.slice(CLIP_ID_PREFIX.length) || null : null;
}

/**
 * How the player may move from one track to the next. Automix analyses YouTube audio, so it only
 * mixes two YouTube tracks; a plain crossfade works for any two with audio of their own; a clip (the
 * DJ's voice) is a hard cut on both sides, so a line is never blended into the music. With Automix
 * ON, only 'automix' pairs blend: Automix owns the transitions, so any other pair is a hard cut
 * (the plain crossfade setting applies when Automix is off).
 */
export type MixPolicy = 'automix' | 'crossfade' | 'cut';
export function mixPolicy(from: Pick<Track, 'source'>, to: Pick<Track, 'source'>): MixPolicy {
  const a = sourceOf(from);
  const b = sourceOf(to);
  if (a === 'clip' || b === 'clip') return 'cut';
  return a === 'youtube' && b === 'youtube' ? 'automix' : 'crossfade';
}

/** Any `auden:` id: not a YouTube video, whatever the track says. */
export function isSyntheticId(trackId: string): boolean {
  return trackId.startsWith('auden:');
}

// ---- The local file provider (Phase 2 fills it) ------------------------------------

/**
 * Finds the file for a track: a pure local track's own file, or the file
 * linked to a YouTube track. Null when there is none, or it can't be read
 * without asking. It runs without a user gesture (a crossfade, Automix), so
 * it may only *query* a folder's permission, never request it.
 */
export type LocalResolver = (track: Track) => Promise<Blob | null>;

let localResolver: LocalResolver | null = null;
let linkProbe: LinkProbe | null = null;

/**
 * Whether a YouTube track has a file that can be read right now, answered from memory without waiting. It
 * keeps the common case (no linked file) on YouTube's synchronous path, and tells Automix which tracks
 * will play from a file. A probe that errs towards false only costs the file being skipped.
 */
export type LinkProbe = (track: Track) => boolean;

export function setLocalResolver(resolver: LocalResolver | null, hasLink?: LinkProbe | null): void {
  localResolver = resolver;
  linkProbe = resolver ? (hasLink ?? null) : null;
}

export function getLinkProbe(): LinkProbe | null {
  return linkProbe;
}

export function getLocalResolver(): LocalResolver | null {
  return localResolver;
}

// ---- The offline provider (Phase 12) -----------------------------------------------------

/**
 * Copies of YouTube songs kept in this browser (auden/offline). A copy is the very stream YouTube serves, so
 * unlike a linked file it doesn't change what the track *is*: it just plays from the copy when that is
 * better than streaming. `has` answers from memory, `read` hands the bytes over.
 */
export interface OfflineProvider {
  has: LinkProbe;
  read: LocalResolver;
}

let offlineProvider: OfflineProvider | null = null;

export function setOfflineProvider(provider: OfflineProvider | null): void {
  offlineProvider = provider;
}

export function getOfflineProvider(): OfflineProvider | null {
  return offlineProvider;
}

/**
 * Whether [track] plays from its offline copy now. Not in a party (everyone plays one stream) and not when
 * Automix is mixing over a live connection (it analyses the stream, so a copy would only make the track a
 * hard cut); with no connection a copy always plays, mix or not.
 */
export function offlineCopyPlays(track: Track, ctx: Pick<MediumContext, 'inParty' | 'offline' | 'preferStream' | 'mixing'>): boolean {
  return !!ctx.offline && !ctx.inParty && !ctx.preferStream && !ctx.mixing && ctx.offline.has(track);
}

// ---- Choosing the medium -------------------------------------------------------------

export interface MediumContext {
  /** In a Listen Together party everyone plays the same YouTube stream: linked files sit it out. */
  inParty: boolean;
  /** The in-memory clip registry (playback/clips.ts). */
  clip: (clipId: string) => Blob | null;
  local: LocalResolver | null;
  /** See [LinkProbe]. Absent means "any YouTube track may have a file" (ask the resolver). */
  hasLink?: LinkProbe | null;
  /** Play the YouTube stream even if a file is linked (Automix's second deck must match what it analysed). */
  preferStream?: boolean;
  /** Copies kept for offline playback (see [OfflineProvider]). */
  offline?: OfflineProvider | null;
  /** Automix is on and the network is up: it analyses the stream, so a copy is left unused. */
  mixing?: boolean;
}

/** What to try, before anything is read. `fallback` is what to play if the file can't be. */
export type MediumPlan =
  | { kind: 'youtube' }
  | { kind: 'file'; getBlob: () => Promise<Blob | null>; fallback: 'youtube' | null }
  | { kind: 'unavailable'; reason: string };

/** What plays, once the file (if any) has been read. */
export type Medium =
  | { kind: 'youtube'; /** Why a linked file was passed over, for the log. */ fellBack?: string }
  | { kind: 'file'; blob: Blob; fallback: 'youtube' | null }
  | { kind: 'unavailable'; reason: string };

export const CLIP_GONE = 'This clip is no longer available.';
export const FILE_UNAVAILABLE = 'This file isn’t available here.';

/** The decision, without reading anything (pure). */
export function resolveMedium(track: Track, ctx: MediumContext): MediumPlan {
  const source = sourceOf(track);
  if (source === 'clip') {
    const id = clipIdOf(track.videoId);
    const blob = id ? ctx.clip(id) : null;
    return blob ? { kind: 'file', getBlob: () => Promise.resolve(blob), fallback: null } : { kind: 'unavailable', reason: CLIP_GONE };
  }
  const local = ctx.local;
  if (source === 'local') {
    return local ? { kind: 'file', getBlob: () => local(track), fallback: null } : { kind: 'unavailable', reason: FILE_UNAVAILABLE };
  }
  // A YouTube song. A synthetic id here is a malformed track; YouTube can't play it either.
  if (isSyntheticId(track.videoId)) return { kind: 'unavailable', reason: FILE_UNAVAILABLE };
  if (ctx.inParty || ctx.preferStream) return { kind: 'youtube' };
  const linked = !!local && (!ctx.hasLink || ctx.hasLink(track));
  const kept = offlineCopyPlays(track, ctx);
  if (!linked && !kept) return { kind: 'youtube' };
  if (!kept) return { kind: 'file', getBlob: () => local!(track), fallback: 'youtube' };
  const offline = ctx.offline!;
  if (!linked) return { kind: 'file', getBlob: () => offline.read(track), fallback: 'youtube' };
  // A linked file first; if it can't be read, the offline copy, then (the caller's fallback) YouTube.
  return {
    kind: 'file',
    fallback: 'youtube',
    getBlob: async () => {
      let failure: unknown = null;
      try {
        const file = await local!(track);
        if (file) return file;
      } catch (error) {
        failure = error;
      }
      const copy = await offline.read(track).catch((error: unknown) => (failure ??= error, null));
      if (copy) return copy;
      if (failure) throw failure;
      return null;
    },
  };
}

/** How long the provider gets to hand over a file before a linked track plays from YouTube instead. */
export const LOCAL_RESOLVE_TIMEOUT_MS = 5_000;

/**
 * Reads the file the plan points at. A linked file that's missing, refused
 * (permission, NotReadableError) or slow falls back to YouTube; a track with
 * nothing to fall back on comes back unavailable.
 */
export function obtainMedium(track: Track, ctx: MediumContext, timeoutMs = LOCAL_RESOLVE_TIMEOUT_MS): Promise<Medium> {
  return readMedium(resolveMedium(track, ctx), timeoutMs);
}

/** [obtainMedium] for a plan already made: callers that can go on at once when there's nothing to read use this. */
export async function readMedium(plan: MediumPlan, timeoutMs = LOCAL_RESOLVE_TIMEOUT_MS): Promise<Medium> {
  if (plan.kind !== 'file') return plan;
  let problem: string;
  try {
    const blob = await withTimeout(plan.getBlob(), timeoutMs);
    if (blob) return { kind: 'file', blob, fallback: plan.fallback };
    problem = 'no file';
  } catch (error) {
    problem = error instanceof Error ? `${error.name}: ${error.message}` : String(error);
  }
  return plan.fallback === 'youtube' ? { kind: 'youtube', fellBack: problem } : { kind: 'unavailable', reason: FILE_UNAVAILABLE };
}

function withTimeout<T>(promise: Promise<T>, ms: number): Promise<T> {
  return new Promise<T>((resolve, reject) => {
    const timer = setTimeout(() => reject(new Error(`the file took longer than ${ms / 1000}s`)), ms);
    promise.then(
      (value) => {
        clearTimeout(timer);
        resolve(value);
      },
      (error: unknown) => {
        clearTimeout(timer);
        reject(error);
      },
    );
  });
}
