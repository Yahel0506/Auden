import { SupersededError } from './superseded';
import { trackLog } from './trackLog';
import type { LoadedTrack, StreamStats } from './youtube/engine';

/** Waiting longer than this for a file to be playable means it isn't going to be (engine.ts's FIRST_AUDIO_TIMEOUT_MS). */
export const FILE_FIRST_AUDIO_TIMEOUT_MS = 15_000;

/** HTMLMediaElement's readyState values, without needing the DOM's constants. */
const HAVE_METADATA = 1;
const HAVE_FUTURE_DATA = 3;

/** The audio itself, or a way to get it (read from a folder, say) when the load starts. */
export type BlobSource = Blob | (() => Promise<Blob>);

export interface FileLoadOptions {
  blob: BlobSource;
  startSec?: number;
  autoplay?: boolean;
}

export interface ObjectUrls {
  create: (blob: Blob) => string;
  revoke: (url: string) => void;
}

const browserUrls: ObjectUrls = {
  create: (blob) => URL.createObjectURL(blob),
  revoke: (url) => URL.revokeObjectURL(url),
};

/**
 * A file, or a clip, played on a deck's own `<audio>` element from a `blob:`
 * URL: the element the YouTube engine uses too, so Automix's Web Audio graph
 * (one createMediaElementSource per element) carries it the same way.
 *
 * It shares the element, so it must be the only one using it: the deck
 * (playback/deck.ts) releases the YouTube engine before a file loads, and
 * stops this engine before YouTube loads. stop() only clears the element when
 * the element is still playing this engine's URL.
 */
export class FileEngine {
  readonly audio: HTMLAudioElement;
  private generation = 0;
  private url: string | null = null;
  private loading = false;
  /** The id playing, once a load finished. */
  private current: string | null = null;
  /** Rejects the load in progress, when a newer load or a stop replaces it. */
  private interrupt: (() => void) | null = null;
  private readonly urls: ObjectUrls;
  private readonly timeoutMs: number;

  /** Raised when the playing file fails after it started (a decode error halfway through). */
  onFatal: (id: string, message: string) => void = () => {};

  constructor(audio: HTMLAudioElement, { urls = browserUrls, timeoutMs = FILE_FIRST_AUDIO_TIMEOUT_MS }: { urls?: ObjectUrls; timeoutMs?: number } = {}) {
    this.audio = audio;
    this.urls = urls;
    this.timeoutMs = timeoutMs;
    audio.addEventListener('error', () => {
      // Only the file this engine is playing; a load reports its own errors.
      const id = this.current;
      if (!id || this.loading || !this.url || audio.src !== this.url) return;
      this.current = null;
      const message = mediaErrorMessage(audio.error);
      trackLog.e(`file playback failed: ${message}`, id);
      this.onFatal(id, message);
    });
  }

  /**
   * Plays [id] from its blob, at [startSec]. Resolves once the element can
   * play; rejects with the element's error, a timeout, or [SupersededError]
   * if another load or a stop replaced this one.
   */
  async load(id: string, { blob, startSec = 0, autoplay = true }: FileLoadOptions): Promise<LoadedTrack> {
    const generation = ++this.generation;
    this.interrupt?.();
    this.current = null;
    this.loading = true;
    const started = performance.now();
    let timer: ReturnType<typeof setTimeout> | undefined;
    // Every wait races this: a newer load or a stop, or the clock running out.
    const guard = new Promise<never>((_, reject) => {
      this.interrupt = () => reject(new SupersededError());
      timer = setTimeout(() => reject(new Error(`no audio within ${this.timeoutMs / 1000}s`)), this.timeoutMs);
    });
    guard.catch(() => {});
    const audio = this.audio;
    try {
      const data = typeof blob === 'function' ? await Promise.race([blob(), guard]) : blob;
      this.check(generation);
      const previous = this.url;
      const url = this.urls.create(data);
      this.url = url;
      audio.src = url;
      if (previous) this.urls.revoke(previous);

      await Promise.race([this.until(audio, 'loadedmetadata', HAVE_METADATA), guard]);
      this.check(generation);
      if (startSec > 0) {
        const end = Number.isFinite(audio.duration) ? audio.duration : startSec;
        audio.currentTime = Math.min(startSec, Math.max(0, end - 0.25));
      }
      await Promise.race([this.until(audio, 'canplay', HAVE_FUTURE_DATA), guard]);
      this.check(generation);

      if (autoplay) {
        await Promise.race([
          audio.play().catch((error: unknown) => {
            // Autoplay without a gesture is refused; the file is loaded and the
            // play button will start it (engine.ts does the same).
            if (error instanceof DOMException && error.name === 'NotAllowedError') {
              trackLog.w('autoplay was blocked by the browser; waiting for a tap', id);
            } else throw error;
          }),
          guard,
        ]);
        this.check(generation);
      }

      this.current = id;
      const track = describe(id, data, audio.duration);
      trackLog.i(`playing ${track.format}, ready after ${Math.round(performance.now() - started)}ms`, id);
      return track;
    } catch (error) {
      if (generation !== this.generation) throw error instanceof SupersededError ? error : new SupersededError();
      trackLog.e(`file load failed: ${error instanceof Error ? error.message : String(error)}`, id);
      // Leave nothing half-loaded on the element.
      this.dropUrl();
      throw error;
    } finally {
      clearTimeout(timer);
      if (generation === this.generation) {
        this.loading = false;
        this.interrupt = null;
      }
    }
  }

  /** Stops whatever this engine is playing or loading. Leaves the element alone when it plays something else. */
  async stop(): Promise<void> {
    this.generation++;
    this.current = null;
    this.loading = false;
    this.interrupt?.();
    this.interrupt = null;
    this.dropUrl();
  }

  private check(generation: number) {
    if (generation !== this.generation) throw new SupersededError();
  }

  /** Empties the element if it's still on this engine's URL, and revokes the URL. */
  private dropUrl() {
    const url = this.url;
    if (!url) return;
    this.url = null;
    const audio = this.audio;
    if (audio.src === url) {
      audio.pause();
      audio.removeAttribute('src');
      audio.load();
    }
    this.urls.revoke(url);
  }

  /** Resolves when the element reaches [readyState] (or is there already); rejects on its error event. */
  private until(audio: HTMLAudioElement, event: 'loadedmetadata' | 'canplay', readyState: number): Promise<void> {
    if (audio.readyState >= readyState) return Promise.resolve();
    return new Promise((resolve, reject) => {
      const cleanup = () => {
        audio.removeEventListener(event, onReady);
        audio.removeEventListener('error', onError);
      };
      const onReady = () => {
        cleanup();
        resolve();
      };
      const onError = () => {
        cleanup();
        reject(new Error(mediaErrorMessage(audio.error)));
      };
      audio.addEventListener(event, onReady);
      audio.addEventListener('error', onError);
    });
  }
}

const MEDIA_ERRORS: Record<number, string> = {
  1: 'the load was aborted',
  2: 'a network error stopped the file',
  3: 'the file couldn’t be decoded',
  4: 'this browser can’t play this file',
};

function mediaErrorMessage(error: MediaError | null): string {
  if (!error) return 'the file couldn’t be played';
  const base = MEDIA_ERRORS[error.code] ?? `media error ${error.code}`;
  return error.message ? `${base} (${error.message})` : base;
}

/** "audio/mpeg" → "MP3": what stats for nerds calls it. */
const CODECS: Record<string, string> = {
  'audio/mpeg': 'MP3',
  'audio/mp3': 'MP3',
  'audio/flac': 'FLAC',
  'audio/x-flac': 'FLAC',
  'audio/ogg': 'Ogg',
  'audio/opus': 'Opus',
  'audio/webm': 'WebM',
  'audio/mp4': 'AAC',
  'audio/aac': 'AAC',
  'audio/x-m4a': 'AAC',
  'audio/wav': 'WAV',
  'audio/x-wav': 'WAV',
  'audio/wave': 'WAV',
};

function describe(id: string, blob: Blob, duration: number): LoadedTrack {
  const durationSec = Number.isFinite(duration) && duration > 0 ? duration : 0;
  const type = blob.type.split(';')[0]!.trim().toLowerCase();
  const codec = CODECS[type] ?? (type ? type.replace(/^audio\//, '').toUpperCase() : null);
  const bitrateKbps = durationSec ? Math.round((blob.size * 8) / durationSec / 1000) || null : null;
  const stream: StreamStats = { codec, itag: null, bitrateKbps, sampleRateHz: null, channels: null, mode: 'file' };
  return {
    videoId: id,
    title: '',
    author: '',
    durationSec: Math.round(durationSec),
    thumbnails: [],
    client: 'local',
    format: [codec ?? 'file', bitrateKbps ? `${bitrateKbps}kbps` : null, 'file'].filter(Boolean).join(' '),
    stream,
    loudnessDb: null,
  };
}
