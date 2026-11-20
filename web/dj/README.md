# auden-dj

A Cloudflare Worker that reads what a listener asked the DJ for ("algo tranquilo de los 90, sin reggaetón") and
answers with a JSON plan (`artists`, `exclude_genres`, `songs`, `decades`, `mood`, `search_queries`, `strict`…). The
app (`DjInterpreter`) holds every name against the listener's library before anything is played; the model never
picks songs. It runs on Cloudflare Workers AI, so there is no API key and no bill within the free daily allowance.

```bash
cd web/dj && npm install
npx wrangler deploy
```

Then set the Worker's URL for a build, in `local.properties` or the environment, and rebuild:

```
DJ_INTERPRETER_URL=https://auden-dj.<your-subdomain>.workers.dev
```

Without it the DJ keeps using its keyword reader alone. The Worker rate-limits by IP (20 asks a minute) and caps
the request at 8 KB and the ask at 300 characters.
