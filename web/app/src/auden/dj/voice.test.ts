import { beforeEach, describe, expect, it } from 'vitest';
import { decodePcm, envelope, resetVoiceForTests, speak, wavBlob } from './voice';

beforeEach(() => resetVoiceForTests());
const bytes = (...n: number[]) => new Uint8Array(n);

describe('pcm / wav', () => {
  it('decodes little-endian 16-bit samples, dropping a stray byte', () => {
    const s = decodePcm(bytes(0x00, 0x40, 0x00, 0xc0, 0x7f));
    expect(s).toHaveLength(2);
    expect(s[0]).toBeCloseTo(0.5, 4);
    expect(s[1]).toBeCloseTo(-0.5, 4);
    expect(decodePcm(bytes())).toHaveLength(0);
  });
  it('writes a 44-byte RIFF header for mono 24 kHz 16-bit', async () => {
    const blob = wavBlob(new Float32Array([0, 0.5, -0.5]), 24000);
    const v = new DataView(await blob.arrayBuffer());
    expect(blob.size).toBe(44 + 6);
    expect(String.fromCharCode(v.getUint8(0), v.getUint8(1), v.getUint8(2), v.getUint8(3))).toBe('RIFF');
    expect(v.getUint32(4, true)).toBe(36 + 6);
    expect(v.getUint32(24, true)).toBe(24000);
    expect(v.getUint16(22, true)).toBe(1);
    expect(v.getUint16(34, true)).toBe(16);
    expect(v.getUint32(40, true)).toBe(6);
    expect(v.getInt16(46, true)).toBe(Math.round(0.5 * 32767));
  });
  it('envelope: 0..1, loudest bin is 1, silence is 0', () => {
    const loud = new Float32Array(1200).fill(0.1);
    loud.fill(0.8, 600);
    const e = envelope(loud, 4);
    expect(Math.max(...e)).toBeCloseTo(1, 5);
    expect(e[0]!).toBeLessThan(e[3]!);
    expect([...envelope(new Float32Array(100), 4)]).toEqual([0, 0, 0, 0]);
  });
});

describe('speak', () => {
  const ok = (data: Uint8Array) => (async () => new Response(data.buffer as ArrayBuffer, { status: 200, headers: { 'content-type': 'audio/pcm' } })) as unknown as typeof fetch;
  it('turns PCM into a WAV clip with a duration and an envelope', async () => {
    const r = await speak('Hi there.', 'en', { fetchImpl: ok(new Uint8Array(48_000)) }); // 1 s of silence
    expect('clip' in r && r.clip.durationMs).toBe(1000);
    expect('clip' in r && r.clip.blob.type).toBe('audio/wav');
  });
  it('a 200 that is not audio (a SPA fallback page) is not played', async () => {
    const html = (async () => new Response('<html></html>', { status: 200, headers: { 'content-type': 'text/html' } })) as unknown as typeof fetch;
    expect(await speak('a', 'en', { fetchImpl: html })).toEqual({ failed: 'unknown' });
  });
  it('no key: says so once and stops asking', async () => {
    let calls = 0;
    const f = (async () => {
      calls++;
      return new Response(JSON.stringify({ error: 'no_key' }), { status: 503 });
    }) as unknown as typeof fetch;
    expect(await speak('a', 'en', { fetchImpl: f })).toEqual({ failed: 'no_key' });
    expect(await speak('b', 'en', { fetchImpl: f })).toEqual({ failed: 'no_key' });
    expect(calls).toBe(1);
  });
  it('a 404 (no Function on this host) is treated like no key', async () => {
    let calls = 0;
    const f = (async () => {
      calls++;
      return new Response('not found', { status: 404 });
    }) as unknown as typeof fetch;
    expect(await speak('a', 'en', { fetchImpl: f })).toEqual({ failed: 'no_key' });
    await speak('b', 'en', { fetchImpl: f });
    expect(calls).toBe(1);
  });
  it('maps server codes, an empty body, a network error and a timeout to failures, never throwing', async () => {
    const code = (error: string) => (async () => new Response(JSON.stringify({ error }), { status: 502 })) as unknown as typeof fetch;
    expect(await speak('a', 'en', { fetchImpl: code('no_credit') })).toEqual({ failed: 'no_credit' });
    expect(await speak('a', 'en', { fetchImpl: code('weird') })).toEqual({ failed: 'unknown' });
    expect(await speak('a', 'en', { fetchImpl: ok(new Uint8Array(0)) })).toEqual({ failed: 'empty' });
    expect(await speak('a', 'en', { fetchImpl: (async () => { throw new Error('x'); }) as never })).toEqual({ failed: 'network' });
    const hang = ((_u: unknown, init: RequestInit) => new Promise((_, rej) => init.signal!.addEventListener('abort', () => rej(new Error('aborted'))))) as unknown as typeof fetch;
    expect(await speak('a', 'en', { fetchImpl: hang, timeoutMs: 10 })).toEqual({ failed: 'timeout' });
  });
});
