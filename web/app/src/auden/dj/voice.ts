import { SAMPLE_RATE } from './voiceProxy';

/** The page's side of the DJ's voice: ask the proxy for a line, get a WAV blob the player can queue, and its loudness shape. */

export const VOICE_ENDPOINT = '/api/dj/voice';
/** A line that takes longer than this is not worth the wait: the DJ plays on with its words on screen (Android: 25 s). */
export const VOICE_TIMEOUT_MS = 25_000;
export const ENVELOPE_BINS = 120;

export type VoiceFailure = 'no_key' | 'rate_limited' | 'no_credit' | 'key_rejected' | 'busy' | 'network' | 'timeout' | 'empty' | 'unknown';

export interface Clip {
  blob: Blob;
  durationMs: number;
  /** 120 loudness bins in 0..1 (sqrt-normalised), for drawing the line before it is heard. */
  envelope: Float32Array;
}

/** 16-bit little-endian mono PCM to samples in [-1, 1]; a stray last byte is half a sample and is dropped. */
export function decodePcm(bytes: Uint8Array): Float32Array {
  const n = Math.floor(bytes.length / 2);
  const out = new Float32Array(n);
  const view = new DataView(bytes.buffer, bytes.byteOffset, bytes.byteLength);
  for (let i = 0; i < n; i++) out[i] = view.getInt16(2 * i, true) / 32768;
  return out;
}

/** 16-bit mono PCM in a WAV container: what every browser's <audio> reads without a codec. */
export function wavBlob(samples: Float32Array, sampleRate: number = SAMPLE_RATE): Blob {
  const dataBytes = samples.length * 2;
  const buf = new ArrayBuffer(44 + dataBytes);
  const v = new DataView(buf);
  const text = (at: number, s: string) => [...s].forEach((c, i) => v.setUint8(at + i, c.charCodeAt(0)));
  text(0, 'RIFF');
  v.setUint32(4, 36 + dataBytes, true);
  text(8, 'WAVE');
  text(12, 'fmt ');
  v.setUint32(16, 16, true);
  v.setUint16(20, 1, true); // PCM
  v.setUint16(22, 1, true); // mono
  v.setUint32(24, sampleRate, true);
  v.setUint32(28, sampleRate * 2, true);
  v.setUint16(32, 2, true);
  v.setUint16(34, 16, true);
  text(36, 'data');
  v.setUint32(40, dataBytes, true);
  for (let i = 0; i < samples.length; i++) v.setInt16(44 + 2 * i, Math.round(Math.max(-1, Math.min(1, samples[i]!)) * 32767), true);
  return new Blob([buf], { type: 'audio/wav' });
}

/** Loudness shape: RMS per bin, scaled so the loudest bin is 1, then square-rooted so quiet syllables still show. */
export function envelope(samples: Float32Array, bins: number = ENVELOPE_BINS): Float32Array {
  const out = new Float32Array(bins);
  if (!samples.length) return out;
  let peak = 0;
  for (let b = 0; b < bins; b++) {
    const from = Math.floor((b * samples.length) / bins);
    const to = Math.max(from + 1, Math.floor(((b + 1) * samples.length) / bins));
    let sum = 0;
    for (let i = from; i < to && i < samples.length; i++) sum += samples[i]! * samples[i]!;
    out[b] = Math.sqrt(sum / (to - from));
    peak = Math.max(peak, out[b]!);
  }
  if (peak > 0) for (let b = 0; b < bins; b++) out[b] = Math.sqrt(out[b]! / peak);
  return out;
}

export type SpeakResult = { clip: Clip } | { failed: VoiceFailure };

let noKey = false; // the server said it has no key: stop asking for this page's life

/** Renders one line. Never throws: a failure is a code, and the DJ goes on without the voice. */
export async function speak(text: string, language: 'es' | 'en', opts: { endpoint?: string; signal?: AbortSignal; fetchImpl?: typeof fetch; timeoutMs?: number } = {}): Promise<SpeakResult> {
  if (noKey) return { failed: 'no_key' };
  const timeout = new AbortController();
  const timer = setTimeout(() => timeout.abort(), opts.timeoutMs ?? VOICE_TIMEOUT_MS);
  const onAbort = () => timeout.abort();
  opts.signal?.addEventListener('abort', onAbort);
  try {
    const response = await (opts.fetchImpl ?? fetch)(opts.endpoint ?? VOICE_ENDPOINT, {
      method: 'POST',
      headers: { 'content-type': 'application/json' },
      body: JSON.stringify({ text, language }),
      signal: timeout.signal,
    });
    if (response.status === 404) {
      // No Pages Function here (`vite dev`, a static host): the voice is not available at all, so stop asking.
      noKey = true;
      return { failed: 'no_key' };
    }
    if (!response.ok) {
      const code = ((await response.json().catch(() => ({}))) as { error?: string }).error;
      if (code === 'no_key') noKey = true;
      return { failed: code === 'no_key' || code === 'rate_limited' || code === 'no_credit' || code === 'key_rejected' || code === 'busy' ? code : 'unknown' };
    }
    // Only audio: a host that answers 200 with a page (a SPA fallback) must not be played as noise.
    if (!(response.headers.get('content-type') ?? '').includes('audio/')) return { failed: 'unknown' };
    const samples = decodePcm(new Uint8Array(await response.arrayBuffer()));
    if (!samples.length) return { failed: 'empty' };
    return { clip: { blob: wavBlob(samples), durationMs: Math.round((samples.length * 1000) / SAMPLE_RATE), envelope: envelope(samples) } };
  } catch {
    return { failed: opts.signal?.aborted ? 'network' : timeout.signal.aborted ? 'timeout' : 'network' };
  } finally {
    clearTimeout(timer);
    opts.signal?.removeEventListener('abort', onAbort);
  }
}

/** Test seam. */
export function resetVoiceForTests(): void {
  noKey = false;
}
