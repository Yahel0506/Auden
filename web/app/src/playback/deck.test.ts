import { describe, expect, it, vi } from 'vitest';
import { MediaDeck, type FileDeckEngine, type YouTubeDeckEngine } from './deck';
import { SupersededError } from './superseded';
import type { LoadedTrack } from './youtube/engine';

const loaded = (videoId: string, mode: 'file' | 'direct'): LoadedTrack => ({
  videoId,
  title: '',
  author: '',
  durationSec: 1,
  thumbnails: [],
  client: mode === 'file' ? 'local' : 'VISIONOS',
  format: '',
  stream: { codec: null, itag: null, bitrateKbps: null, sampleRateHz: null, channels: null, mode },
  loudnessDb: null,
});

interface Deferred<T> {
  promise: Promise<T>;
  resolve: (value: T) => void;
  reject: (error: unknown) => void;
}

/** A promise and the hands that settle it. */
function deferred<T = void>(): Deferred<T> {
  let resolve!: (value: T) => void;
  let reject!: (error: unknown) => void;
  const promise = new Promise<T>((res, rej) => {
    resolve = res;
    reject = rej;
  });
  return { promise, resolve, reject };
}

function setup() {
  const log: string[] = [];
  const yt = {
    released: false,
    load: vi.fn(async (id: string) => {
      log.push(`yt.load ${id}`);
      if (yt.released) throw new SupersededError();
      return loaded(id, 'direct');
    }),
    stop: vi.fn(async () => {
      log.push('yt.stop');
    }),
    release: vi.fn(async () => {
      log.push('yt.release');
      yt.released = true;
      await yt.releaseGate?.promise;
      log.push('yt.released');
    }),
    claim: vi.fn(() => {
      log.push('yt.claim');
      yt.released = false;
    }),
    releaseGate: null as Deferred<void> | null,
  } satisfies YouTubeDeckEngine & Record<string, unknown>;
  const file = {
    load: vi.fn(async (id: string): Promise<LoadedTrack> => {
      log.push(`file.load ${id}`);
      if (file.next) return file.next(id);
      return loaded(id, 'file');
    }),
    stop: vi.fn(async () => {
      log.push('file.stop');
    }),
    next: null as ((id: string) => Promise<LoadedTrack>) | null,
  } as {
    load: ReturnType<typeof vi.fn<(id: string) => Promise<LoadedTrack>>>;
    stop: ReturnType<typeof vi.fn<() => Promise<void>>>;
    next: ((id: string) => Promise<LoadedTrack>) | null;
  } satisfies FileDeckEngine & Record<string, unknown>;
  const createYouTube = vi.fn(async () => yt);
  const deck = new MediaDeck(file, createYouTube);
  return { log, yt, file, deck, createYouTube };
}

const blob = new Blob(['x']);

describe('MediaDeck', () => {
  it('never makes the YouTube engine for files alone', async () => {
    const { deck, createYouTube, log } = setup();
    await deck.load({ kind: 'file', blob }, 'auden:local:1');
    await deck.load({ kind: 'file', blob }, 'auden:local:2');
    await deck.stop();
    expect(createYouTube).not.toHaveBeenCalled();
    expect(deck.mode).toBe('file');
    expect(log).toEqual(['file.load auden:local:1', 'file.load auden:local:2', 'file.stop']);
  });

  it('makes the YouTube engine once, on the first YouTube load', async () => {
    const { deck, createYouTube } = setup();
    await Promise.all([deck.load({ kind: 'youtube' }, 'a').catch(() => {}), deck.load({ kind: 'youtube' }, 'b')]);
    expect(createYouTube).toHaveBeenCalledOnce();
    expect(deck.mode).toBe('youtube');
  });

  it('releases YouTube before a file, and stops the file before YouTube', async () => {
    const { deck, log } = setup();
    await deck.load({ kind: 'youtube' }, 'v1');
    await deck.load({ kind: 'file', blob }, 'auden:local:1');
    await deck.load({ kind: 'youtube' }, 'v2');
    expect(log).toEqual([
      'yt.claim',
      'yt.load v1',
      'yt.release',
      'yt.released',
      'file.load auden:local:1',
      'file.stop',
      'yt.claim',
      'yt.load v2',
    ]);
  });

  it('stops whichever engine owns the element', async () => {
    const { deck, yt, file } = setup();
    await deck.load({ kind: 'youtube' }, 'v1');
    await deck.stop();
    expect(yt.stop).toHaveBeenCalledOnce();
    await deck.load({ kind: 'file', blob }, 'f');
    await deck.stop();
    expect(file.stop).toHaveBeenCalledOnce();
    expect(yt.stop).toHaveBeenCalledOnce();
  });

  it('a YouTube load never overlaps a release still under way', async () => {
    const { deck, yt, log } = setup();
    await deck.load({ kind: 'youtube' }, 'v1');
    yt.releaseGate = deferred();
    const toFile = deck.load({ kind: 'file', blob }, 'f');
    await vi.waitFor(() => expect(log.at(-1)).toBe('yt.release'));
    const toYouTube = deck.load({ kind: 'youtube' }, 'v2');
    // Nothing reaches the element while Shaka is still detaching.
    await new Promise((r) => setTimeout(r, 0));
    expect(log.at(-1)).toBe('yt.release');
    yt.releaseGate.resolve();
    await expect(toFile).rejects.toMatchObject({ name: 'SupersededError' });
    await expect(toYouTube).resolves.toMatchObject({ videoId: 'v2' });
    // The file never loaded: the newer YouTube load took the deck back once the release finished.
    expect(log.slice(2)).toEqual(['yt.release', 'yt.released', 'file.stop', 'yt.claim', 'yt.load v2']);
  });

  it('a load superseded before its turn never switches the deck', async () => {
    const { deck, yt, log } = setup();
    await deck.load({ kind: 'youtube' }, 'v1');
    const toFile = deck.load({ kind: 'file', blob }, 'f');
    const toYouTube = deck.load({ kind: 'youtube' }, 'v2');
    await expect(toFile).rejects.toMatchObject({ name: 'SupersededError' });
    await toYouTube;
    expect(yt.release).not.toHaveBeenCalled();
    expect(log.slice(2)).toEqual(['yt.claim', 'yt.load v2']);
  });

  it('falls back to YouTube when a linked file fails', async () => {
    const { deck, file, log } = setup();
    const fallback = vi.fn();
    deck.onFallback = fallback;
    file.next = async () => {
      throw new Error('NotReadableError');
    };
    await expect(deck.load({ kind: 'file', blob, fallback: true }, 'v1')).resolves.toMatchObject({ videoId: 'v1', stream: { mode: 'direct' } });
    expect(fallback).toHaveBeenCalledOnce();
    expect(log).toEqual(['file.load v1', 'file.stop', 'yt.claim', 'yt.load v1']);
  });

  it('does not fall back without leave to', async () => {
    const { deck, file, yt } = setup();
    file.next = async () => {
      throw new Error('decode');
    };
    await expect(deck.load({ kind: 'file', blob }, 'auden:local:1')).rejects.toThrow('decode');
    expect(yt.load).not.toHaveBeenCalled();
  });

  it('a file that fails after a newer load started does not fall back over it', async () => {
    const { deck, file, yt } = setup();
    const failing = deferred<LoadedTrack>();
    file.next = () => failing.promise;
    const first = deck.load({ kind: 'file', blob, fallback: true }, 'v1');
    await vi.waitFor(() => expect(file.load).toHaveBeenCalledOnce());
    file.next = null;
    const second = deck.load({ kind: 'file', blob }, 'auden:local:2');
    failing.reject(new Error('timeout'));
    await expect(first).rejects.toMatchObject({ name: 'SupersededError' });
    await expect(second).resolves.toMatchObject({ videoId: 'auden:local:2' });
    expect(yt.load).not.toHaveBeenCalled();
  });

  it('a stop supersedes a load still waiting for the lock', async () => {
    const { deck, yt, file } = setup();
    await deck.load({ kind: 'youtube' }, 'v1');
    const toFile = deck.load({ kind: 'file', blob }, 'f');
    const stopped = deck.stop();
    await expect(toFile).rejects.toMatchObject({ name: 'SupersededError' });
    await stopped;
    expect(file.load).not.toHaveBeenCalled();
    expect(yt.release).not.toHaveBeenCalled();
    expect(yt.stop).toHaveBeenCalledOnce();
  });

  it('a stop during a release waits for it, then stops the file side', async () => {
    const { deck, yt, file } = setup();
    await deck.load({ kind: 'youtube' }, 'v1');
    yt.releaseGate = deferred();
    const toFile = deck.load({ kind: 'file', blob }, 'f');
    await vi.waitFor(() => expect(yt.release).toHaveBeenCalledOnce());
    const stopped = deck.stop();
    yt.releaseGate.resolve();
    await expect(toFile).rejects.toMatchObject({ name: 'SupersededError' });
    await stopped;
    expect(file.load).not.toHaveBeenCalled();
    expect(file.stop).toHaveBeenCalledOnce();
    expect(yt.stop).not.toHaveBeenCalled();
  });

  it('refuses a load whose caller saw an older deck', async () => {
    const { deck, file } = setup();
    const since = deck.version;
    void deck.stop();
    await expect(deck.load({ kind: 'file', blob }, 'f', {}, since)).rejects.toMatchObject({ name: 'SupersededError' });
    expect(file.load).not.toHaveBeenCalled();
  });

  it('a failed YouTube engine build still stops the file it would replace', async () => {
    const { file } = setup();
    const deck = new MediaDeck(file, () => Promise.reject(new Error('chunk failed')));
    await deck.load({ kind: 'file', blob }, 'f');
    await expect(deck.load({ kind: 'youtube' }, 'v')).rejects.toThrow('chunk failed');
    expect(file.stop).toHaveBeenCalledOnce();
  });
});
