import type { BlobSource } from './fileEngine';
import { SupersededError, isSuperseded } from './superseded';
import type { LoadedTrack } from './youtube/engine';

/**
 * One deck: one `<audio>` element and the two engines that can play on it,
 * YouTube (Shaka over MSE) and files (a `blob:` URL). The element is single
 * use for Web Audio (Automix's graph wraps it once), so both engines share
 * it, and only one may own it at a time: Shaka clears the element's src
 * whenever it unloads, and a file's src would otherwise land under a live
 * MediaSource.
 *
 * [load] and [stop] are the only ways to drive either engine. Switching to a
 * file releases the YouTube engine (detached from the element) before the
 * blob is set; switching to YouTube stops the file engine before Shaka
 * attaches again, which only a load that's still current may do. The switch
 * itself runs under a promise-chain lock, so a release can never overlap an
 * attach; the load after it runs outside the lock, so a newer load supersedes
 * a slow one at once, as before.
 */

export type DeckMode = 'youtube' | 'file' | null;

/** What the deck is asked to play. `fallback`: play from YouTube instead if the file won't. */
export type DeckMedium = { kind: 'youtube' } | { kind: 'file'; blob: BlobSource; fallback?: boolean };

export interface DeckLoadOptions {
  startSec?: number;
  autoplay?: boolean;
}

/** What the deck needs of the YouTube engine (playback/youtube/engine.ts). */
export interface YouTubeDeckEngine {
  load(videoId: string, options?: DeckLoadOptions): Promise<LoadedTrack>;
  stop(): Promise<void>;
  /** Off the element: detached, and nothing of its own (a recovery) may attach it again. */
  release(): Promise<void>;
  /** The deck's to use again: the next load may attach. */
  claim(): void;
}

/** What the deck needs of the file engine (playback/fileEngine.ts). */
export interface FileDeckEngine {
  load(id: string, options: { blob: BlobSource } & DeckLoadOptions): Promise<LoadedTrack>;
  stop(): Promise<void>;
}

export class MediaDeck<Y extends YouTubeDeckEngine = YouTubeDeckEngine, F extends FileDeckEngine = FileDeckEngine> {
  /** Which engine owns the element; null until the first load. */
  mode: DeckMode = null;
  private yt: Y | null = null;
  private ytCreating: Promise<Y> | null = null;
  private chain: Promise<unknown> = Promise.resolve();
  /** Bumped by every load and stop: a load whose number is old is superseded ([version]). */
  private ticket = 0;

  /** A linked file failed and the deck moved on to YouTube; for the log. */
  onFallback: (id: string, error: unknown) => void = () => {};

  constructor(
    readonly file: F,
    /** Builds the YouTube engine on first use, so a session of files alone never loads Shaka. */
    private readonly createYouTube: () => Promise<Y>,
  ) {}

  /** The YouTube engine, once one was made (it may be released). */
  get youtube(): Y | null {
    return this.yt;
  }

  /**
   * Bumped by every load and stop. A caller that has to wait before it loads
   * (reading a file) takes it first and passes it as [since]: a load or stop
   * in between means its load is no longer wanted.
   */
  get version(): number {
    return this.ticket;
  }

  async load(medium: DeckMedium, id: string, options: DeckLoadOptions = {}, since?: number): Promise<LoadedTrack> {
    if (since !== undefined && since !== this.ticket) throw new SupersededError('The deck moved on before this load started');
    const ticket = ++this.ticket;
    if (medium.kind === 'file') {
      try {
        await this.switchTo('file', ticket);
        this.check(ticket);
        return await this.file.load(id, { ...options, blob: medium.blob });
      } catch (error) {
        if (isSuperseded(error) || ticket !== this.ticket) throw error instanceof SupersededError ? error : new SupersededError();
        if (!medium.fallback) throw error;
        this.onFallback(id, error);
      }
    }
    const yt = await this.switchTo('youtube', ticket);
    this.check(ticket);
    return yt!.load(id, options);
  }

  /** Stops whichever engine owns the element; supersedes a load in progress. */
  stop(): Promise<void> {
    ++this.ticket;
    return this.exclusive(async () => {
      if (this.mode === 'file') await this.file.stop();
      else if (this.mode === 'youtube') await this.yt?.stop();
    });
  }

  private check(ticket: number) {
    if (ticket !== this.ticket) throw new SupersededError();
  }

  private async switchTo(target: 'youtube' | 'file', ticket: number): Promise<Y | null> {
    const result = await this.exclusive(async () => {
      this.check(ticket);
      if (target === 'file') {
        if (this.mode === 'youtube') await this.yt?.release();
        this.mode = 'file';
        return null;
      }
      let yt: Y;
      try {
        yt = await this.ensureYouTube();
      } catch (error) {
        // No YouTube engine to hand the element to: at least stop the file it would replace.
        if (this.mode === 'file') await this.file.stop();
        throw error;
      }
      this.check(ticket);
      if (this.mode === 'file') await this.file.stop();
      yt.claim();
      this.mode = 'youtube';
      return yt;
    });
    this.check(ticket);
    return result;
  }

  private async ensureYouTube(): Promise<Y> {
    if (this.yt) return this.yt;
    this.ytCreating ??= this.createYouTube();
    try {
      this.yt = await this.ytCreating;
      return this.yt;
    } finally {
      this.ytCreating = null;
    }
  }

  /** Runs [task] after every task queued before it; a failure doesn't break the chain. */
  private exclusive<T>(task: () => Promise<T>): Promise<T> {
    const run = this.chain.then(task);
    this.chain = run.catch(() => {});
    return run;
  }
}
