import { botguard } from '@/playback/youtube/botguard';
import { ytAuthSnapshot } from '@/extension/useYtAuth';
import { getInnertube, getSignedInnertube } from '@/playback/youtube/innertube';
import { resolvePlayer, UnplayableError, usesWebPo } from '@/playback/youtube/resolver';

/**
 * A picture wider than this is a music video; a square one is YouTube's art
 * track for a song (the player's own rule, YouTubeEngine.MIN_VIDEO_ASPECT).
 */
const MIN_VIDEO_ASPECT = 1.2;

/** The track's audio, and whether YouTube serves it as a music video rather than a song. */
export interface TrackAudio {
  bytes: ArrayBuffer;
  musicVideo: boolean;
}

/** googlevideo serves a direct URL in ranges; Android (vivi/InnerTubeX) asks for 512 KiB–1 MiB at a time. */
const CHUNK_BYTES = 1024 * 1024;
const MAX_BYTES = 40 * 1024 * 1024;
const MAX_REFUSALS = 2;

/** What a finished stream was: the format picked, for whoever stores the bytes. */
export interface AudioStreamInfo {
  itag: number;
  mime: string;
  total: number;
  musicVideo: boolean;
  /** The format's `lmt`, so a resume can tell the file changed under it. */
  modified: string | null;
}

/** A refusal to stream that is about this client or this track, not the network. */
export class AudioStreamError extends Error {
  override readonly name = 'AudioStreamError';
  constructor(
    message: string,
    /** `sabr`: no direct URL; `webpo`: only a web client answered (cut at ~70 s); `changed`: the file changed mid-resume. */
    readonly kind: 'sabr' | 'webpo' | 'changed' | 'http',
    readonly status?: number,
  ) {
    super(message);
  }
}

export interface StreamOptions {
  signal?: AbortSignal;
  /** Called with each chunk, in order, as it arrives. Return a promise to apply back-pressure. */
  onChunk: (chunk: Uint8Array, at: number, info: AudioStreamInfo) => void | Promise<void>;
  /** Stop (without failing) once this many bytes have been read; the player-side analysis caps at 40 MiB. */
  maxBytes?: number;
  /** Refuse a web (WebPO) client: its streams are cut at ~70 s even over direct URLs (docs/youtube-playback.md). */
  directClientOnly?: boolean;
}

/**
 * [videoId]'s audio, delivered chunk by chunk: the same direct URL Shaka plays (VISIONOS), fetched in ranges.
 * A 403 mid-way gets a fresh URL for the same itag and carries on from the same byte; a different file
 * behind the fresh URL (another size or `lmt`) is an [AudioStreamError] `changed`, never spliced.
 */
export async function streamTrackAudio(videoId: string, { signal, onChunk, maxBytes = Infinity, directClientOnly = false }: StreamOptions): Promise<AudioStreamInfo> {
  let source = await resolveAudioUrl(videoId, undefined, directClientOnly);
  const first = source;
  let at = 0;
  let refusals = 0;
  for (;;) {
    const { url, total } = source;
    const end = total ? Math.min(total, at + CHUNK_BYTES) - 1 : at + CHUNK_BYTES - 1;
    url.searchParams.set('range', `${at}-${end}`);
    const response = await fetch(url, { signal, credentials: 'omit' });
    if (response.status === 403 && refusals < MAX_REFUSALS) {
      // googlevideo sometimes refuses a URL part-way through; a fresh one
      // (another player response) carries on from the same byte.
      refusals++;
      source = await resolveAudioUrl(videoId, source.itag, directClientOnly);
      // Only a difference between two known values is a different file.
      if ((source.total && first.total && source.total !== first.total) || (source.modified && first.modified && source.modified !== first.modified)) {
        throw new AudioStreamError('the file changed between requests', 'changed');
      }
      continue;
    }
    if (!response.ok) throw new AudioStreamError(`audio fetch HTTP ${response.status} at byte ${at}`, 'http', response.status);
    const part = new Uint8Array(await response.arrayBuffer());
    await onChunk(part, at, first);
    at += part.byteLength;
    if (part.byteLength === 0 || (total ? at >= total : part.byteLength < CHUNK_BYTES) || at > maxBytes) break;
  }
  return first;
}

/**
 * The whole audio file of [videoId], for analysis. Opus in WebM when there is one (every
 * browser that runs the app decodes it in decodeAudioData), else AAC.
 * Throws when the track only streams over SABR, which has no file to fetch.
 */
export async function fetchTrackAudio(videoId: string, signal?: AbortSignal): Promise<TrackAudio> {
  const parts: Uint8Array[] = [];
  let length = 0;
  const info = await streamTrackAudio(videoId, {
    signal,
    maxBytes: MAX_BYTES,
    onChunk: (chunk) => {
      parts.push(chunk);
      length += chunk.byteLength;
    },
  });
  const out = new Uint8Array(length);
  let offset = 0;
  for (const part of parts) {
    out.set(part, offset);
    offset += part.byteLength;
  }
  return { bytes: out.buffer, musicVideo: info.musicVideo };
}

/** A deciphered direct URL for the track's best audio (or the same itag, when resuming). */
async function resolveAudioUrl(videoId: string, itag?: number, directClientOnly = false): Promise<AudioStreamInfo & { url: URL }> {
  const { info, client, yt } = await resolveAsPlayerWould(videoId);
  if (directClientOnly && usesWebPo(client)) throw new AudioStreamError(`only ${client} answered, and its streams are cut short`, 'webpo');
  const audio = (info.streaming_data?.adaptive_formats ?? []).filter(
    (f) => f.has_audio && !f.has_video && (f.url || f.signature_cipher || f.cipher) && !f.is_drc,
  );
  const pick = (mime: string) =>
    audio.filter((f) => f.mime_type.startsWith(mime)).sort((a, b) => b.bitrate - a.bitrate)[0];
  const format = (itag !== undefined ? audio.find((f) => f.itag === itag) : undefined) ?? pick('audio/webm') ?? pick('audio/mp4');
  if (!format) throw new AudioStreamError('no direct audio URL (SABR only)', 'sabr');
  if (itag !== undefined && format.itag !== itag) throw new AudioStreamError('the format changed between requests', 'changed');

  const url = new URL(await format.decipher(yt.session.player));
  if (usesWebPo(client)) {
    const pot = await botguard.mint(videoId);
    if (pot) url.searchParams.set('pot', pot);
  }
  const pictures = (info.streaming_data?.adaptive_formats ?? []).filter((f) => f.has_video && f.width && f.height);
  const widest = pictures.sort((a, b) => b.width! * b.height! - a.width! * a.height!)[0];
  const musicVideo = !!widest && widest.width! / widest.height! >= MIN_VIDEO_ASPECT;
  const modified = url.searchParams.get('lmt') ?? (format.last_modified_ms ? String(format.last_modified_ms) : null);
  return { url, total: format.content_length ?? 0, itag: format.itag, mime: format.mime_type, musicVideo, modified };
}

/**
 * The player's own walk (YouTubeEngine.load): anonymously first, and when
 * YouTube calls that an age gate (which is also how its "sign in to confirm
 * you're not a bot" reads), again as the signed-in user. Without the second
 * step a song that plays fine could never be analysed.
 */
async function resolveAsPlayerWould(videoId: string) {
  try {
    return await resolvePlayer(await getInnertube(), videoId);
  } catch (error) {
    if (!(error instanceof UnplayableError && error.ageGated) || !ytAuthSnapshot().signedIn) throw error;
    return resolvePlayer(await getSignedInnertube(), videoId, new Set(), true);
  }
}
