import { describe, expect, it, vi } from 'vitest';
import { FISH_MODEL, MAX_TEXT_CHARS, RATE_LIMIT, VOICES, handleVoice, originAllowed, parseVoiceRequest } from './voiceProxy';

const URL_ = 'https://auden.example/api/dj/voice';
const req = (body: unknown, init: { method?: string; origin?: string | null; type?: string; ip?: string } = {}) =>
  new Request(URL_, {
    method: init.method ?? 'POST',
    headers: {
      ...(init.origin === null ? {} : { origin: init.origin ?? 'https://auden.example' }),
      'content-type': init.type ?? 'application/json',
      'cf-connecting-ip': init.ip ?? '1.1.1.1',
    },
    body: init.method === 'GET' ? null : typeof body === 'string' ? body : JSON.stringify(body),
  });
const body = (b: unknown) => b;
const pcm = () => new Response(new Uint8Array([0, 64, 0, 192]), { status: 200 });
const deps = (fetchImpl: typeof fetch) => ({ fetch: fetchImpl, now: () => 1_000_000, hits: new Map<string, number[]>() });
const env = { FISH_API_KEY: 'sekret' };

describe('request validation', () => {
  it('accepts a line in a supported language and squeezes whitespace', () => {
    expect(parseVoiceRequest({ text: '  Good   evening. ', language: 'en' })).toEqual({ text: 'Good evening.', language: 'en' });
  });
  it('refuses other languages, empty or over-long text, and non-objects', () => {
    expect(parseVoiceRequest({ text: 'hi', language: 'fr' })).toEqual({ error: 'bad_language' });
    expect(parseVoiceRequest({ text: '   ', language: 'es' })).toEqual({ error: 'empty_text' });
    expect(parseVoiceRequest({ text: 'x'.repeat(MAX_TEXT_CHARS + 1), language: 'es' })).toEqual({ error: 'text_too_long' });
    expect(parseVoiceRequest(null)).toEqual({ error: 'bad_request' });
    expect(parseVoiceRequest({ text: 5, language: 'en' })).toEqual({ error: 'bad_request' });
  });
  it('same-origin only', () => {
    expect(originAllowed('https://auden.example', URL_)).toBe(true);
    expect(originAllowed('https://evil.example', URL_)).toBe(false);
    expect(originAllowed(null, URL_)).toBe(false);
    expect(originAllowed('not a url', URL_)).toBe(false);
  });
});

describe('handleVoice', () => {
  it('without the key it says no_key and never calls Fish', async () => {
    const f = vi.fn();
    const r = await handleVoice(req({ text: 'hi', language: 'en' }), {}, deps(f as never));
    expect(r.status).toBe(503);
    expect(await r.json()).toEqual({ error: 'no_key' });
    expect(f).not.toHaveBeenCalled();
  });
  it('sends the free model header, the key as a bearer token and the language’s voice, and streams PCM back', async () => {
    const f = vi.fn(async (_u: unknown, _i?: RequestInit) => pcm());
    const r = await handleVoice(req({ text: 'Buenas noches.', language: 'es' }), env, deps(f as never));
    expect(r.status).toBe(200);
    expect(r.headers.get('content-type')).toBe('audio/pcm');
    expect(r.headers.get('x-sample-rate')).toBe('24000');
    expect(new Uint8Array(await r.arrayBuffer())).toEqual(new Uint8Array([0, 64, 0, 192]));
    const [, init] = f.mock.calls[0]!;
    const headers = init!.headers as Record<string, string>;
    expect(headers.authorization).toBe('Bearer sekret');
    expect(headers.model).toBe(FISH_MODEL);
    expect(JSON.parse(init!.body as string)).toMatchObject({ text: 'Buenas noches.', reference_id: VOICES.es, format: 'pcm', sample_rate: 24000, language: 'es' });
  });
  it('never leaks the key or Fish’s message in an error', async () => {
    const f = vi.fn(async () => new Response('bad key sekret', { status: 401 }));
    const r = await handleVoice(req({ text: 'hi', language: 'en' }), env, deps(f as never));
    expect(r.status).toBe(502);
    const text = await r.text();
    expect(text).toBe(JSON.stringify({ error: 'key_rejected' }));
    expect(text).not.toContain('sekret');
  });
  it('maps upstream limits and failures to codes', async () => {
    const code = async (status: number) => (await (await handleVoice(req({ text: 'hi', language: 'en' }), env, deps((async () => new Response('', { status })) as never))).json()) as { error: string };
    expect(await code(402)).toEqual({ error: 'no_credit' });
    expect(await code(429)).toEqual({ error: 'rate_limited' });
    expect(await code(503)).toEqual({ error: 'busy' });
    expect(await code(500)).toEqual({ error: 'http_500' });
    const down = await handleVoice(req({ text: 'hi', language: 'en' }), env, deps((async () => { throw new Error('x'); }) as never));
    expect(down.status).toBe(502);
  });
  it('refuses other origins, other methods, wrong content types and huge bodies before spending anything', async () => {
    const f = vi.fn();
    const d = deps(f as never);
    expect((await handleVoice(req({ text: 'hi', language: 'en' }, { origin: 'https://evil.example' }), env, d)).status).toBe(403);
    expect((await handleVoice(req({ text: 'hi', language: 'en' }, { origin: null }), env, d)).status).toBe(403);
    expect((await handleVoice(req({}, { method: 'GET' }), env, d)).status).toBe(405);
    expect((await handleVoice(req({ text: 'hi', language: 'en' }, { type: 'text/plain' }), env, d)).status).toBe(415);
    expect((await handleVoice(req({ text: 'x'.repeat(3000), language: 'en' }), env, d)).status).toBe(413);
    expect(f).not.toHaveBeenCalled();
  });
  it('rate limits per address, not globally', async () => {
    const d = deps((async () => pcm()) as never);
    for (let i = 0; i < RATE_LIMIT.max; i++) expect((await handleVoice(req({ text: 'hi', language: 'en' }), env, d)).status).toBe(200);
    const over = await handleVoice(req({ text: 'hi', language: 'en' }), env, d);
    expect(over.status).toBe(429);
    expect(over.headers.get('retry-after')).toBe('60');
    expect((await handleVoice(req({ text: 'hi', language: 'en' }, { ip: '2.2.2.2' }), env, d)).status).toBe(200);
  });
  it('with ALLOWED_HOST set, another host is refused; x-forwarded-for is not trusted for the limit', async () => {
    const d = deps((async () => pcm()) as never);
    expect((await handleVoice(req({ text: 'hi', language: 'en' }), { ...env, ALLOWED_HOST: 'other.example' }, d)).status).toBe(403);
    expect((await handleVoice(req({ text: 'hi', language: 'en' }), { ...env, ALLOWED_HOST: 'auden.example' }, d)).status).toBe(200);
    const spoof = (ip: string) => new Request(URL_, { method: 'POST', headers: { origin: 'https://auden.example', 'content-type': 'application/json', 'x-forwarded-for': ip }, body: JSON.stringify({ text: 'hi', language: 'en' }) });
    const d2 = deps((async () => pcm()) as never);
    for (let i = 0; i < RATE_LIMIT.max; i++) await handleVoice(spoof(`9.9.9.${i}`), env, d2);
    expect((await handleVoice(spoof('8.8.8.8'), env, d2)).status).toBe(429);
  });
  it('bad JSON is a 400', async () => {
    const r = await handleVoice(new Request(URL_, { method: 'POST', headers: { origin: 'https://auden.example', 'content-type': 'application/json' }, body: '{nope' }), env, deps((async () => pcm()) as never));
    expect(r.status).toBe(400);
  });
});
