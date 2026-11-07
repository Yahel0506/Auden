import { beforeEach, describe, expect, it, vi } from 'vitest';

// Shaka, YouTube.js, googlevideo and BotGuard replaced by stand-ins: these
// tests are about who owns the element, not about streaming.
const h = vi.hoisted(() => {
  type Gate = { promise: Promise<void>; resolve: () => void };
  const gate = (): Gate => {
    let resolve!: () => void;
    const promise = new Promise<void>((r) => (resolve = r));
    return { promise, resolve };
  };
  const state = {
    players: [] as FakePlayer[],
    attachGate: null as Gate | null,
    loadGate: null as { promise: Promise<void>; reject: (e: unknown) => void } | null,
    resolvePlayer: vi.fn(),
    excludeClient: vi.fn(),
    gate,
  };
  class FakePlayer {
    static isBrowserSupported = () => true;
    listeners = new Map<string, (event: unknown) => void>();
    attach = vi.fn(async () => {
      await state.attachGate?.promise;
    });
    detach = vi.fn(async () => {});
    unload = vi.fn(async () => {});
    load = vi.fn(async () => {
      await state.loadGate?.promise;
    });
    configure() {}
    addEventListener(type: string, listener: (event: unknown) => void) {
      this.listeners.set(type, listener);
    }
    getVariantTracks() {
      return [];
    }
    seekRange() {
      return { start: 0, end: 0 };
    }
    retryStreaming() {}
    constructor() {
      state.players.push(this);
    }
  }
  class ShakaError {
    static Severity = { RECOVERABLE: 1, CRITICAL: 2 };
    static Code = { BAD_HTTP_STATUS: 1001, HTTP_ERROR: 1002 };
    constructor(
      public code: number,
      public severity: number,
      public data: unknown[],
    ) {}
  }
  return { state, FakePlayer, ShakaError };
});

vi.mock('shaka-player/dist/shaka-player.compiled', () => ({
  default: {
    polyfill: { installAll() {} },
    Player: h.FakePlayer,
    net: { NetworkingEngine: { registerScheme() {}, PluginPriority: { FALLBACK: 1 } }, HttpFetchPlugin: { parse() {} } },
    util: { Error: h.ShakaError },
  },
}));
vi.mock('googlevideo/sabr-streaming-adapter', () => ({ SabrStreamingAdapter: class {} }));
vi.mock('googlevideo/utils', () => ({ buildSabrFormat: () => ({}) }));
vi.mock('youtubei.js/web', () => ({ Constants: {}, Utils: {}, YT: {} }));
vi.mock('./ShakaPlayerAdapter', () => ({ ShakaPlayerAdapter: class {} }));
vi.mock('./botguard', () => ({ botguard: { init: async () => {}, mint: async () => 'pot', coldStartToken: async () => 'cold' } }));
vi.mock('./innertube', () => ({ getInnertube: async () => ({}), getSignedInnertube: async () => ({}) }));
vi.mock('@/extension/useYtAuth', () => ({ ytAuthSnapshot: () => ({ signedIn: false }) }));
vi.mock('./resolver', () => ({
  CLIENT_ATTEMPTS: 1,
  usesWebPo: () => false,
  excludeClient: h.state.excludeClient,
  playerRequest: async () => ({}),
  resolvePlayer: h.state.resolvePlayer,
  UnplayableError: class extends Error {
    override readonly name = 'UnplayableError';
    ageGated = false;
  },
}));

import { YouTubeEngine } from './engine';

/** A response with direct audio URLs, so a load goes straight to Shaka. */
const resolved = (videoId: string) => ({
  videoId,
  client: 'VISIONOS',
  signed: false,
  info: {
    streaming_data: { adaptive_formats: [{ has_audio: true, has_video: false, url: 'https://example/audio' }] },
    toDash: async () => '<MPD/>',
    basic_info: { title: 'T', author: 'A', duration: 100, thumbnail: [] },
    player_config: null,
  },
});

/** An <audio> that already has data: firstAudio() resolves at once. */
const audio = () => ({ readyState: 4, currentTime: 0, paused: true, play: async () => {} }) as unknown as HTMLAudioElement;

beforeEach(() => {
  h.state.players.length = 0;
  h.state.attachGate = null;
  h.state.loadGate = null;
  h.state.resolvePlayer.mockReset();
  h.state.resolvePlayer.mockImplementation(async (_yt: unknown, videoId: string) => resolved(videoId));
  h.state.excludeClient.mockReset();
  vi.stubGlobal('HTMLMediaElement', { HAVE_FUTURE_DATA: 3 });
});

describe('YouTubeEngine release', () => {
  it('refuses to load while released, and attaches again once claimed', async () => {
    const engine = new YouTubeEngine(audio());
    await engine.load('v1');
    const player = h.state.players[0]!;
    expect(player.attach).toHaveBeenCalledOnce();

    await engine.release();
    expect(player.detach).toHaveBeenCalledOnce();
    await expect(engine.load('v2')).rejects.toMatchObject({ name: 'SupersededError' });
    expect(player.attach).toHaveBeenCalledOnce();

    engine.claim();
    await expect(engine.load('v3')).resolves.toMatchObject({ videoId: 'v3' });
    expect(player.attach).toHaveBeenCalledTimes(2);
    expect(h.state.players).toHaveLength(1);
  });

  it('waits for an attach under way before detaching, and supersedes its load', async () => {
    const engine = new YouTubeEngine(audio());
    h.state.attachGate = h.state.gate();
    const loading = engine.load('v1');
    await vi.waitFor(() => expect(h.state.players[0]?.attach).toHaveBeenCalled());
    const player = h.state.players[0]!;
    const releasing = engine.release();
    await Promise.resolve();
    expect(player.detach).not.toHaveBeenCalled();
    h.state.attachGate.resolve();
    await releasing;
    expect(player.detach).toHaveBeenCalledOnce();
    await expect(loading).rejects.toMatchObject({ name: 'SupersededError' });
    expect(player.load).not.toHaveBeenCalled();
  });

  it('stop() leaves a released engine’s element alone', async () => {
    const engine = new YouTubeEngine(audio());
    await engine.load('v1');
    const player = h.state.players[0]!;
    player.unload.mockClear();
    await engine.release();
    await engine.stop();
    expect(player.unload).not.toHaveBeenCalled();
  });

  it('a load interrupted by a release does not blame the client', async () => {
    const engine = new YouTubeEngine(audio());
    let reject!: (e: unknown) => void;
    h.state.loadGate = { promise: new Promise<void>((_, r) => (reject = r)), reject: (e) => reject(e) };
    const loading = engine.load('v1');
    await vi.waitFor(() => expect(h.state.players[0]?.load).toHaveBeenCalled());
    await engine.release();
    // What Shaka says when its load is cut short.
    h.state.loadGate.reject(new h.ShakaError(7000, 2, []));
    await expect(loading).rejects.toMatchObject({ name: 'SupersededError' });
    expect(h.state.excludeClient).not.toHaveBeenCalled();
  });

  it('a stream refusal after a release does not reload or stop anything', async () => {
    const engine = new YouTubeEngine(audio());
    await engine.load('v1');
    const player = h.state.players[0]!;
    await engine.release();
    player.unload.mockClear();
    const load = vi.spyOn(engine, 'load');
    const fatal = vi.fn();
    engine.onFatal = fatal;
    player.listeners.get('error')!({ detail: new h.ShakaError(1001, 2, ['https://x', 403]) });
    await new Promise((r) => setTimeout(r, 0));
    expect(load).not.toHaveBeenCalled();
    expect(player.unload).not.toHaveBeenCalled();
    expect(h.state.excludeClient).not.toHaveBeenCalled();
    expect(fatal).not.toHaveBeenCalled();
  });

  it('a stream refusal while playing still moves to the next client (unchanged)', async () => {
    const engine = new YouTubeEngine(audio());
    await engine.load('v1');
    const player = h.state.players[0]!;
    const recovered = vi.fn();
    engine.onRecovered = recovered;
    player.listeners.get('error')!({ detail: new h.ShakaError(1001, 2, ['https://x', 403]) });
    await vi.waitFor(() => expect(recovered).toHaveBeenCalledOnce());
    expect(h.state.excludeClient).toHaveBeenCalledWith('v1', 'VISIONOS', 'stream error 1001', false);
  });
});
