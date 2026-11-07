import { afterEach, describe, expect, it, vi } from 'vitest';
import { FileEngine, type ObjectUrls } from './fileEngine';

type Behaviour = 'ok' | 'hang' | 'decode-error';

/** An <audio> stand-in: setting src plays out the events the behaviour asks for. */
class FakeAudio extends EventTarget {
  behaviour: Behaviour = 'ok';
  readyState = 0;
  currentTime = 0;
  duration = NaN;
  paused = true;
  error: { code: number; message: string } | null = null;
  playResult: () => Promise<void> = async () => {
    this.paused = false;
  };
  play = vi.fn(() => this.playResult());
  pause = vi.fn(() => {
    this.paused = true;
  });
  load = vi.fn();
  private value = '';

  get src() {
    return this.value;
  }
  set src(url: string) {
    this.value = url;
    this.readyState = 0;
    const behaviour = this.behaviour;
    if (behaviour === 'hang') return;
    setTimeout(() => {
      if (this.value !== url) return;
      if (behaviour === 'decode-error') {
        this.error = { code: 3, message: 'bad frame' };
        this.dispatchEvent(new Event('error'));
        return;
      }
      this.duration = 200;
      this.readyState = 1;
      this.dispatchEvent(new Event('loadedmetadata'));
      setTimeout(() => {
        if (this.value !== url) return;
        this.readyState = 4;
        this.dispatchEvent(new Event('canplay'));
      }, 0);
    }, 0);
  }
  removeAttribute(name: string) {
    if (name === 'src') this.value = '';
  }
}

function setup(options: { timeoutMs?: number } = {}) {
  const audio = new FakeAudio();
  let n = 0;
  const urls: ObjectUrls & { revoked: string[] } = {
    revoked: [],
    create: () => `blob:test/${++n}`,
    revoke(url) {
      this.revoked.push(url);
    },
  };
  const engine = new FileEngine(audio as unknown as HTMLAudioElement, { urls, ...options });
  return { audio, urls, engine };
}

const blob = new Blob([new Uint8Array(200 * 40_000)], { type: 'audio/mpeg' });

afterEach(() => vi.useRealTimers());

describe('FileEngine', () => {
  it('loads a blob, seeks, plays and describes it', async () => {
    const { audio, engine } = setup();
    const track = await engine.load('auden:local:1', { blob, startSec: 30 });
    expect(audio.src).toBe('blob:test/1');
    expect(audio.currentTime).toBe(30);
    expect(audio.play).toHaveBeenCalledOnce();
    expect(track).toMatchObject({ videoId: 'auden:local:1', client: 'local', durationSec: 200, stream: { mode: 'file', codec: 'MP3', bitrateKbps: 320 } });
  });

  it('gets the blob from a provider', async () => {
    const { engine } = setup();
    const provider = vi.fn(async () => blob);
    await engine.load('x', { blob: provider, autoplay: false });
    expect(provider).toHaveBeenCalledOnce();
  });

  it('stays loaded when autoplay is refused', async () => {
    const { audio, engine } = setup();
    audio.playResult = () => Promise.reject(new DOMException('no gesture', 'NotAllowedError'));
    await expect(engine.load('x', { blob })).resolves.toMatchObject({ videoId: 'x' });
  });

  it('rejects other play() failures', async () => {
    const { audio, engine } = setup();
    audio.playResult = () => Promise.reject(new DOMException('nope', 'NotSupportedError'));
    await expect(engine.load('x', { blob })).rejects.toMatchObject({ name: 'NotSupportedError' });
  });

  it('a newer load supersedes the one in progress and revokes its URL', async () => {
    const { audio, urls, engine } = setup();
    audio.behaviour = 'hang';
    const first = engine.load('a', { blob });
    await vi.waitFor(() => expect(audio.src).toBe('blob:test/1'));
    audio.behaviour = 'ok';
    const second = engine.load('b', { blob });
    await expect(first).rejects.toMatchObject({ name: 'SupersededError' });
    await expect(second).resolves.toMatchObject({ videoId: 'b' });
    expect(urls.revoked).toEqual(['blob:test/1']);
    expect(audio.src).toBe('blob:test/2');
  });

  it('a stop supersedes the load, empties the element and revokes the URL', async () => {
    const { audio, urls, engine } = setup();
    audio.behaviour = 'hang';
    const loading = engine.load('a', { blob });
    await vi.waitFor(() => expect(audio.src).toBe('blob:test/1'));
    await engine.stop();
    await expect(loading).rejects.toMatchObject({ name: 'SupersededError' });
    expect(audio.src).toBe('');
    expect(audio.load).toHaveBeenCalled();
    expect(urls.revoked).toEqual(['blob:test/1']);
  });

  it('stop leaves the element alone once something else plays on it', async () => {
    const { audio, urls, engine } = setup();
    await engine.load('a', { blob });
    audio.behaviour = 'hang';
    audio.src = 'blob:mediasource';
    await engine.stop();
    expect(audio.src).toBe('blob:mediasource');
    expect(audio.pause).not.toHaveBeenCalled();
    expect(urls.revoked).toEqual(['blob:test/1']);
  });

  it('a stop during the blob read means the element is never touched', async () => {
    const { audio, engine } = setup();
    let hand: (b: Blob) => void = () => {};
    const loading = engine.load('a', { blob: () => new Promise<Blob>((r) => (hand = r)) });
    await engine.stop();
    hand(blob);
    await expect(loading).rejects.toMatchObject({ name: 'SupersededError' });
    expect(audio.src).toBe('');
  });

  it('times out when the file never becomes playable', async () => {
    vi.useFakeTimers();
    const { audio, urls, engine } = setup({ timeoutMs: 15_000 });
    audio.behaviour = 'hang';
    const loading = engine.load('a', { blob });
    const outcome = expect(loading).rejects.toThrow('no audio within 15s');
    await vi.advanceTimersByTimeAsync(15_000);
    await outcome;
    expect(urls.revoked).toEqual(['blob:test/1']);
    expect(audio.src).toBe('');
  });

  it('rejects with the element’s error', async () => {
    const { audio, engine } = setup();
    audio.behaviour = 'decode-error';
    const loading = engine.load('a', { blob });
    await expect(loading).rejects.toThrow(/couldn’t be decoded/);
    await expect(loading).rejects.not.toMatchObject({ name: 'SupersededError' });
  });

  it('reports a failure after the file started', async () => {
    const { audio, engine } = setup();
    const fatal = vi.fn();
    engine.onFatal = fatal;
    await engine.load('a', { blob });
    audio.error = { code: 3, message: '' };
    audio.dispatchEvent(new Event('error'));
    expect(fatal).toHaveBeenCalledWith('a', 'the file couldn’t be decoded');
  });
});
