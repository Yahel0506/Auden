/**
 * The DJ's voice proxy (the Cloudflare Pages Function at functions/api/dj/voice.ts is a thin wrapper around this).
 * The Fish Audio key lives only in the Function's environment (`FISH_API_KEY`); the page sends a line and a language
 * and gets raw PCM back. Without the key it answers 503 `no_key` and the DJ plays on with its words on screen.
 * Pure of Cloudflare types so it is tested directly.
 */

export const FISH_ENDPOINT = 'https://api.fish.audio/v1/tts';
/** The free tier: an absent or unknown `model` header falls back to the paid model, so this exact string keeps it from billing. */
export const FISH_MODEL = 's2.1-pro-free';
export const SAMPLE_RATE = 24_000;
export const VOICES = { en: '3de8512d76484ddb8f91593c4cfbbc42' /* DJ Cara */, es: '1060f143d4444d9b8cc8ab45e7f6520e' /* Wendy */ } as const;
export type VoiceLanguage = keyof typeof VOICES;

/** A DJ line is a sentence or two; anything longer is not the DJ. */
export const MAX_TEXT_CHARS = 400;
/** Request bodies are tiny; refuse anything bigger before reading it. */
export const MAX_BODY_BYTES = 2_048;
/** Per client address, per isolate: best effort (an isolate can be recycled), on top of Fish's own limits. */
export const RATE_LIMIT = { max: 6, windowMs: 60_000 };

export interface ProxyEnv {
  FISH_API_KEY?: string;
  /** Test seam / alternative endpoint. */
  FISH_API_URL?: string;
  /** When set, only this host may use the endpoint (keeps `*.pages.dev` previews from bypassing a WAF rule on the real domain). */
  ALLOWED_HOST?: string;
}

export interface ProxyDeps {
  fetch: typeof fetch;
  now: () => number;
  /** Where the per-address counters live; a fresh Map per process by default. */
  hits?: Map<string, number[]>;
}

const defaultHits = new Map<string, number[]>();

const json = (status: number, error: string, extra: Record<string, string> = {}) =>
  new Response(JSON.stringify({ error }), { status, headers: { 'content-type': 'application/json', 'cache-control': 'no-store', ...extra } });

export function parseVoiceRequest(raw: unknown): { text: string; language: VoiceLanguage } | { error: string } {
  if (typeof raw !== 'object' || raw === null) return { error: 'bad_request' };
  const { text, language } = raw as { text?: unknown; language?: unknown };
  if (language !== 'en' && language !== 'es') return { error: 'bad_language' };
  if (typeof text !== 'string') return { error: 'bad_request' };
  const clean = text.replace(/\s+/g, ' ').trim();
  if (!clean) return { error: 'empty_text' };
  if (clean.length > MAX_TEXT_CHARS) return { error: 'text_too_long' };
  return { text: clean, language };
}

/** Same-origin only: a page on another site must not spend the key. Requests without an Origin (curl, tests) are refused too. */
export function originAllowed(origin: string | null, requestUrl: string): boolean {
  if (!origin) return false;
  try {
    const o = new URL(origin);
    const r = new URL(requestUrl);
    return o.origin === r.origin;
  } catch {
    return false;
  }
}

/** Records a hit and says whether this one is over the limit. Old hits fall out of the window; empty entries are dropped. */
export function overLimit(hits: Map<string, number[]>, who: string, now: number): boolean {
  const recent = (hits.get(who) ?? []).filter((t) => now - t < RATE_LIMIT.windowMs);
  if (recent.length >= RATE_LIMIT.max) {
    hits.set(who, recent);
    return true;
  }
  recent.push(now);
  hits.set(who, recent);
  // Keep the map from growing without bound on a long-lived isolate.
  if (hits.size > 5_000) for (const [k, v] of hits) if (!v.some((t) => now - t < RATE_LIMIT.windowMs)) hits.delete(k);
  return false;
}

export async function handleVoice(request: Request, env: ProxyEnv, deps: ProxyDeps): Promise<Response> {
  if (request.method !== 'POST') return json(405, 'method_not_allowed', { allow: 'POST' });
  if (!originAllowed(request.headers.get('origin'), request.url)) return json(403, 'forbidden');
  if (env.ALLOWED_HOST && new URL(request.url).host !== env.ALLOWED_HOST) return json(403, 'forbidden');
  if (!(request.headers.get('content-type') ?? '').includes('application/json')) return json(415, 'unsupported_media_type');
  const declared = Number(request.headers.get('content-length') ?? '0');
  if (declared > MAX_BODY_BYTES) return json(413, 'too_large');
  if (!env.FISH_API_KEY) return json(503, 'no_key');

  // Only Cloudflare's own header: x-forwarded-for is whatever the caller wrote.
  const who = request.headers.get('cf-connecting-ip') ?? 'unknown';
  if (overLimit(deps.hits ?? defaultHits, who, deps.now())) return json(429, 'rate_limited', { 'retry-after': String(Math.ceil(RATE_LIMIT.windowMs / 1000)) });

  let raw: unknown;
  try {
    const text = await request.text();
    if (text.length > MAX_BODY_BYTES) return json(413, 'too_large');
    raw = JSON.parse(text);
  } catch {
    return json(400, 'bad_request');
  }
  const parsed = parseVoiceRequest(raw);
  if ('error' in parsed) return json(400, parsed.error);

  let upstream: Response;
  try {
    upstream = await deps.fetch(env.FISH_API_URL ?? FISH_ENDPOINT, {
      method: 'POST',
      headers: { authorization: `Bearer ${env.FISH_API_KEY}`, model: FISH_MODEL, 'content-type': 'application/json' },
      body: JSON.stringify({
        text: parsed.text,
        reference_id: VOICES[parsed.language],
        format: 'pcm',
        sample_rate: SAMPLE_RATE,
        normalize: true,
        latency: 'balanced',
        language: parsed.language,
      }),
    });
  } catch {
    return json(502, 'network');
  }
  if (!upstream.ok) {
    // Fish's own words (and any detail of the key) never reach the page: only a code.
    const code = upstream.status === 401 ? 'key_rejected' : upstream.status === 402 ? 'no_credit' : upstream.status === 429 ? 'rate_limited' : upstream.status === 503 ? 'busy' : `http_${upstream.status}`;
    return json(upstream.status === 429 ? 429 : 502, code);
  }
  return new Response(upstream.body, {
    status: 200,
    headers: { 'content-type': 'audio/pcm', 'x-sample-rate': String(SAMPLE_RATE), 'cache-control': 'no-store' },
  });
}
