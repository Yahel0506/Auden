import { handleVoice, type ProxyEnv } from '../../../app/src/auden/dj/voiceProxy';

/**
 * POST /api/dj/voice  { text, language: 'es' | 'en' }  →  raw 16-bit mono PCM at 24 kHz (audio/pcm).
 *
 * Cloudflare Pages looks for `functions/` at the project's root directory (`web`, see README › Deploy). Secrets and
 * variables (Pages → Settings → Environment variables, or `wrangler pages secret put`):
 *   FISH_API_KEY   the Fish Audio key (secret). Without it the endpoint answers 503 no_key and the DJ speaks with words on screen.
 *   ALLOWED_HOST   optional: the one host that may use the endpoint (e.g. auden.example), so `*.pages.dev` previews cannot.
 * Same-origin only, 400-character lines, rate limited per address; the free model is forced here. See app/src/auden/dj/voiceProxy.ts.
 * Add a Cloudflare WAF rate-limiting rule on this path for a real backstop (it only covers the custom domain).
 */
export const onRequest = (context: { request: Request; env: ProxyEnv }): Promise<Response> => handleVoice(context.request, context.env, { fetch: (input, init) => fetch(input, init), now: () => Date.now() });
