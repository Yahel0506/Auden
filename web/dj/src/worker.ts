// Reads what a listener asked the DJ for and answers with a plan, as JSON the app can obey.
// The model never picks songs: it names artists, songs, decades, genres and mood, and what to leave out. The app
// holds every name against the listener's own library before it plays anything.

interface Env {
  DJ_APP_SECRET: string;
  AI: { run(model: string, input: unknown): Promise<{ response?: unknown }> };
  MODEL: string;
  LIMITER: { limit(options: { key: string }): Promise<{ success: boolean }> };
}

const SYSTEM = `You turn a listener's request to their radio DJ into a JSON plan. Reply with the JSON object only.

Fields (omit what the request does not say):
- artists: artists asked for, as the listener means them (fix spelling and mishearings: "kendrik" -> "Kendrick Lamar").
- exclude_artists, exclude_genres: what they do NOT want ("sin reggaetón", "nada de X", "no rap").
- songs: [{"title","artist"}] songs asked for by name ("pon Prey", "Zombie de The Neighbourhood"). Use the listener's artists and playlists to resolve "el de ..." when you can.
- similar_to: artists to find new music like ("algo parecido a X", "como X").
- decades: e.g. [1990, 2000] for "los 90 y 2000".
- genres: plain genre names in English ("indie rock", "hip hop").
- mood: one of FOCUS, CHILL, ENERGY, ACOUSTIC, PARTY, WORKOUT, ROMANTIC, MELANCHOLY, HAPPY, SLEEP, ROAD_TRIP, NOSTALGIC, LATIN, NIGHT, COFFEE, RAINY.
- playlist: the name of one of the listener's playlists, copied exactly from the list given.
- album: an album named.
- favorites (most played / best), fresh (new / recent), rediscover (forgotten / old favourites), local_only (offline / downloaded): booleans.
- search_queries: 1-3 short searches a person would type into a music service to find what was asked when the library may lack it. Never the whole sentence; never include the listener's own genres unless they asked.
- strict: the fields that must not be dropped if the library falls short (artists, decades, genres, mood). Put what the listener named explicitly there; leave looser wishes out.
- title: a short name for this listening session, 1-4 words, in the listener's language, a noun phrase and never a sentence or a command: the DJ will say it out loud ("Para {title}, empezamos con..."), so it must not repeat the request. Lowercase except proper nouns: "música clásica", "rock de los 80", "algo como Radiohead", "para estudiar", "Prey y Zombie". No "pon", "ponme", "quiero", no exclusions.

Examples (listener's language Spanish unless noted):
"ponme música clásica" -> {"genres":["classical"],"search_queries":["classical music"],"strict":["genres"],"title":"música clásica"}
"algo de jazz para cenar" -> {"genres":["jazz"],"mood":"ROMANTIC","search_queries":["jazz dinner music"],"strict":["genres"],"title":"jazz para cenar"}
"rock de los 80 sin Queen" -> {"genres":["rock"],"decades":[1980],"exclude_artists":["Queen"],"search_queries":["80s rock hits"],"strict":["genres","decades"],"title":"rock de los 80"}
"play something like Radiohead" (English) -> {"similar_to":["Radiohead"],"title":"something like Radiohead"}
"música para estudiar" -> {"mood":"FOCUS","search_queries":["music to study"],"title":"para estudiar"}
"ponme algo" -> {}

A genre, mood, artist, decade, song or activity in the request ALWAYS yields a plan; {} is only for requests with none of them.

Rules: never invent artists the listener did not mention or imply. If the request is vague ("ponme algo", "sorpréndeme"), return {}. The listener's taste is context for resolving names, not a reason to add artists.`;

const STRINGS = { type: "array", items: { type: "string" } };
// The shape the model is held to: it cannot invent keys or mix quotes, which a small model otherwise sometimes does.
const PLAN_SCHEMA = {
  type: "object",
  properties: {
    artists: STRINGS,
    exclude_artists: STRINGS,
    songs: { type: "array", items: { type: "object", properties: { title: { type: "string" }, artist: { type: "string" } }, required: ["title"] } },
    similar_to: STRINGS,
    decades: { type: "array", items: { type: "integer" } },
    genres: STRINGS,
    exclude_genres: STRINGS,
    mood: { type: "string" },
    playlist: { type: "string" },
    album: { type: "string" },
    favorites: { type: "boolean" },
    fresh: { type: "boolean" },
    rediscover: { type: "boolean" },
    local_only: { type: "boolean" },
    search_queries: STRINGS,
    strict: STRINGS,
    title: { type: "string" },
  },
  additionalProperties: false,
};

// The app signs each request with a secret only it (and this Worker) holds: HMAC-SHA256 of "timestamp.body", sent as hex.
// Anything unsigned, wrongly signed or older than five minutes is refused before the model is ever asked. It keeps
// casual use of the URL out; it is not proof against someone who reads the secret out of the APK.
async function signedByTheApp(request: Request, raw: string, secret: string): Promise<boolean> {
  if (!secret) return false;
  const ts = Number(request.headers.get("x-auden-ts"));
  const sig = request.headers.get("x-auden-sig") ?? "";
  if (!Number.isFinite(ts) || Math.abs(Date.now() / 1000 - ts) > 300 || !/^[0-9a-f]{64}$/.test(sig)) return false;
  const key = await crypto.subtle.importKey("raw", new TextEncoder().encode(secret), { name: "HMAC", hash: "SHA-256" }, false, ["verify"]);
  const bytes = new Uint8Array(sig.match(/../g)!.map((h) => parseInt(h, 16)));
  return crypto.subtle.verify("HMAC", key, bytes, new TextEncoder().encode(`${ts}.${raw}`));
}

const CORS = { "access-control-allow-origin": "*", "access-control-allow-headers": "content-type" };

export default {
  async fetch(request: Request, env: Env): Promise<Response> {
    if (request.method === "OPTIONS") return new Response(null, { headers: CORS });
    const url = new URL(request.url);
    if (request.method !== "POST" || url.pathname !== "/interpret") return new Response("not found", { status: 404 });

    const ip = request.headers.get("cf-connecting-ip") ?? "unknown";
    if (env.LIMITER && !(await env.LIMITER.limit({ key: ip })).success) return new Response("slow down", { status: 429 });

    const raw = await request.text();
    if (raw.length > 8_000) return new Response("too large", { status: 413 });
    if (!(await signedByTheApp(request, raw, env.DJ_APP_SECRET))) return new Response("unauthorized", { status: 401 });
    let body: { text?: string; context?: { artists?: string[]; genres?: string[]; playlists?: string[]; language?: string } };
    try { body = JSON.parse(raw); } catch { return new Response("bad request", { status: 400 }); }
    const text = (body.text ?? "").toString().trim().slice(0, 300);
    if (!text) return new Response("{}", { headers: { "content-type": "application/json", ...CORS } });
    const c = body.context ?? {};
    const taste = [
      `Language: ${c.language ?? "en"}`,
      `Most played artists: ${(c.artists ?? []).slice(0, 30).join(", ")}`,
      `Genres: ${(c.genres ?? []).slice(0, 12).join(", ")}`,
      `Playlists: ${(c.playlists ?? []).slice(0, 25).join(" | ")}`,
    ].join("\n");

    let reply: unknown;
    try {
      const result = await env.AI.run(env.MODEL, {
        max_tokens: 500,
        temperature: 0,
        messages: [
          { role: "system", content: SYSTEM },
          { role: "user", content: `${taste}\n\nRequest: ${text}` },
        ],
        response_format: { type: "json_schema", json_schema: PLAN_SCHEMA },
      });
      reply = result.response;
    } catch (e) {
      console.log("model", String(e).slice(0, 300));
      return new Response("upstream", { status: 502 });
    }
    // JSON mode may hand back the object itself; otherwise keep what is between the first brace and the last.
    const raw2 = typeof reply === "string" ? reply : JSON.stringify(reply ?? {});
    const out = raw2.slice(raw2.indexOf("{"), raw2.lastIndexOf("}") + 1);
    try { JSON.parse(out); } catch { return new Response("{}", { headers: { "content-type": "application/json", ...CORS } }); }
    return new Response(out, { headers: { "content-type": "application/json", ...CORS } });
  },
};
