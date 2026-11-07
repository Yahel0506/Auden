# Auden Web

Auden's web player and its companion browser extension: the Android app's music features (YouTube Music and Spotify browsing, a local library, playlists, lyrics, the Auden DJ, listening stats, backup) in a browser. It grew out of BitChord's web player, and some identifiers still carry that name (see [Naming](#naming)). The plan and the decisions behind each phase are in [`docs/AUDEN_WEB_PLAN.md`](../docs/AUDEN_WEB_PLAN.md); the original design prompt is [`docs/web-app-build-prompt.md`](../docs/web-app-build-prompt.md).

| Package | What it is |
|---|---|
| `app/` | The site: Vite, React 19, TypeScript, React Router, TanStack Query, Zustand, Tailwind CSS v4 |
| `extension/` | Manifest V3 extension for Chromium and Firefox, built with WXT. It is the site's only way to reach YouTube Music, Spotify and Discord |
| `shared/` | The page ↔ extension bridge contract and client, used by both |
| `functions/` | Cloudflare Pages Functions. Today one: `api/dj/voice.ts`, the DJ's text-to-speech proxy |
| `docs/` | `apple-music-dom.md` (the design measurements), `automix.md`, `kira-notes.md` (how the reference YouTube web client plays), `youtube-playback.md`, `youtube-signin.md`, `queue-and-player.md`, `lyrics.md`, `spotify-and-discord.md` |
| `fixtures/` | Real captured responses for parser tests (see its README) |
| `scripts/` | `capture-fixtures.mjs` |

## Setup

Node 22 or newer and pnpm (the version is pinned by `packageManager` in `package.json`):

```bash
pnpm --dir web install
```

## Run the site

`vite build` refuses to run without `VITE_PARTY_SERVER_URL`; the dev server does not need it.

```bash
pnpm --dir web/app dev
```

It serves on `http://localhost:5173`, and only there (`strictPort`): the extension trusts that exact origin. Without the extension the site still plays the local library, local playlists, lyrics from files, the DJ over the local library, and backup; YouTube Music, Spotify and Discord need it.

## Run the extension

```bash
pnpm --dir web/extension build
```

- **Chrome, Edge, Brave:** `chrome://extensions` → Developer mode → *Load unpacked* → `web/extension/.output/chrome-mv3`. The id is always `illniemhhmkbiibppncmmknojgloaiha`, pinned by the manifest `key` (`extension/dev-key/`), so the site finds it without configuration.
- **Firefox:** `about:debugging#/runtime/this-firefox` → *Load Temporary Add-on* → `web/extension/.output/firefox-mv3/manifest.json`. Its id is `extension@auden.app`.

Then open `http://localhost:5173/extension`. It shows "Installed", with the version and the transport: **Direct** on Chromium (`externally_connectable`), **Page relay** on Firefox (content script). The page and the extension talk on the `auden-bridge` / `auden-events` channel.

### Publishing the extension from the site

`pnpm --dir web build:site` (what Cloudflare Pages runs) first runs `pnpm --dir web/extension package`, which builds both targets and writes three files to `app/public/downloads/` (git-ignored) before the site is built, so they end up in `app/dist/downloads/`:

- `bitchord-extension-chrome (<version>).zip` and `bitchord-extension-firefox (<version>).zip`. Each holds a single folder with the same name, so extracting it leaves one folder to load unpacked.
- `extension.json`: `{ version, builtAt, files }`. The site compares `version` with the installed extension's and shows **Update** in the sidebar and an update row on the Extension page when the zip is newer. **To publish an update, bump `version` in `extension/package.json`.**

The zip names and the `BITCHORD_*` variable names are identifiers other code matches on, so they stay as they are. `VITE_EXTENSION_CHROME_URL`, `VITE_EXTENSION_FIREFOX_URL` and `VITE_EXTENSION_MANIFEST_URL` replace those links, for example to point at store listings.

Toolbar icons (`extension/public/icon/`) are rendered from `app/public/favicon.svg` by `pnpm --dir web/extension icons`.

## Configuration

| Variable | Where | Secret? | Default / notes |
|---|---|---|---|
| `VITE_PARTY_SERVER_URL` | app build (`app/.env.local`, Pages environment) | no | None. The Listen Together server, `https://host`. **`vite build` fails without it** |
| `VITE_EXTENSION_ID_CHROMIUM` | app build | no | The pinned dev id. Set it for a store-built extension |
| `VITE_EXTENSION_CHROME_URL`, `VITE_EXTENSION_FIREFOX_URL`, `VITE_EXTENSION_MANIFEST_URL` | app build | no | Where the Extension page's download and update links point. Default: the zips and `extension.json` the site itself serves |
| `VITE_ANDROID_APP_URL` | app build | no | The GitHub releases page |
| `BITCHORD_SITE_ORIGINS` | extension build | no | Comma-separated origins the extension serves; **replaces** the default list (see [Domain and origins](#domain-and-origins)) |
| `BITCHORD_STORE_BUILD=1` | extension build | no | Leaves out the dev `key` and the localhost origin, for a Chrome Web Store upload |
| `FISH_API_KEY` | Pages Function `api/dj/voice` (Pages → Settings → Environment variables, encrypted) | **yes** | The Fish Audio key. It exists only in the Function, never in the client bundle. Without it the endpoint answers `503 no_key` and the DJ talks with the words on screen only |
| `ALLOWED_HOST` | Pages Function | no | Optional. The one host allowed to use the voice endpoint (for example the custom domain), so a `*.pages.dev` address cannot dodge a WAF rule |
| `NODE_VERSION=22` | Pages build | no | Required by the build |

There is **no Last.fm key on the web.** The Android DJ uses Last.fm for genres; the web DJ takes the genre from the files' tags instead (`LASTFM_API_KEY` stays an Android build secret in `.github/workflows`). Nothing in `web/` reads it, so there is nothing to configure.

## Deploy (Cloudflare Pages)

Create a Pages project for Auden, connected to the repository:

| Setting | Value |
| --- | --- |
| Root directory | `web` |
| Build command | `pnpm build:site` |
| Output directory | `app/dist` |
| Environment | `NODE_VERSION=22`, `VITE_PARTY_SERVER_URL`, secret `FISH_API_KEY` (and optionally `ALLOWED_HOST`) |

The root directory is `web`, so Pages picks up `web/functions/` (not `web/app/functions/`) as the Functions folder. A rate-limiting WAF rule on `/api/dj/voice` is the real backstop for the Function's best-effort per-isolate limit; add it on the custom domain.

### Open decisions

These are not decided and nothing in the code assumes an answer:

1. **The domain.** Auden has no domain yet. Until it has, the site, the extension's allowed origins and the `www` redirect in `app/index.html` still name the BitChord deployment (`https://bitchord.in`, `https://bitchord-8bw.pages.dev`).
2. **The production origin list.** See below.
3. **Whether to keep the BitChord project** deployed beside Auden's, or retire it.

### Domain and origins

The extension answers only the origins in `extension/site.config.ts`: they become `externally_connectable` (Chromium), the relay content script's `matches` (Firefox) and the network rules' `initiatorDomains`. The default list is `https://bitchord.in`, `https://bitchord-8bw.pages.dev` and `http://localhost:5173`; store builds drop localhost. When Auden's domain is chosen:

```bash
BITCHORD_SITE_ORIGINS=https://auden.example,https://auden.pages.dev,http://localhost:5173 pnpm --dir web build:site
```

(the first origin is where the popup links to), set the same variable in the Pages build environment, update the `www` redirect in `app/index.html`, and bump `extension/package.json`'s `version` so installed extensions are offered the update. Keep the BitChord origins only while that deployment still needs to work with the new extension.

## Checks

From `web/` (CI, `.github/workflows/web.yml`, runs the same):

```bash
pnpm typecheck                                          # app, extension, shared
pnpm test                                               # app, extension, shared
VITE_PARTY_SERVER_URL=https://example.invalid pnpm build  # app (tsc + vite) and both extension targets
VITE_PARTY_SERVER_URL=https://example.invalid pnpm build:site
```

## What it does, and where it lives

Paths are relative to `web/` (`app/src/auden/…` is the Auden-only code).

| Feature (Android equivalent) | Code |
|---|---|
| The database (Room `AudenDb`) as IndexedDB, and the listening stats | `app/src/auden/db/` (schema, DAOs, `DB_VERSION` migrations), `app/src/auden/stats/` |
| Local music folder: scan, identify, link to YouTube ids, covers | `app/src/auden/library/` (`scan/`, `identify/`, `linkIndex`, `localResolver`), `app/src/pages/LocalLibraryPage.tsx` |
| Local playlists, M3U8 / XSPF / Auden JSON import and export, saved copies of YouTube Music and Spotify playlists, auto-sync | `app/src/auden/playlists/` |
| Lyrics: providers, saved lyrics, offsets, batch download | `app/src/auden/lyrics/`, `app/src/auden/jobs/` (the job banner), and `app/src/lyrics/` for the player's side |
| Home: listener shelves mixed with the feed (`HomeComposer`) | `app/src/auden/home/` |
| The Auden DJ: planner, request parser, taste, writer, pool, voice | `app/src/auden/dj/`, `functions/api/dj/voice.ts` |
| Backup and restore in Android's file format | `app/src/auden/backup/` |
| Playback: one deck per audio element, YouTube or file, clips (DJ lines), Automix | `app/src/playback/{source,deck,fileEngine,clips}.ts`, `playback/youtube/` |
| Spotify (library, matching to YouTube, errors) through the extension | `app/src/data/spotify/`, `extension/lib/spotify*.ts`, `app/src/pages/Spotify*Page.tsx` |
| Offline copies (Android downloads, in part): a persistent queue with retry and pause, files in OPFS, played through the `OfflineProvider` seam | `app/src/auden/offline/`, `app/src/pages/OfflinePage.tsx`, `app/src/playback/source.ts` |
| Full-screen player visuals (blurred cover, animated mesh, DJ ring) | `app/src/shell/FullScreenPlayer.tsx`, `app/src/shell/CoverBlurBackdrop.tsx`, `app/src/lib/coverBlur.ts` |

## Android parity

- **Backup file:** `app: "auden"` header (a BitChord file with `"bitchord"` still imports), `SCHEMA_VERSION` 2. The reader refuses what the phone refuses: a missing header, a newer version, a row of the wrong shape. The writer maps what the phone would choke on (whole numbers for every Long/Int, no nulls in non-nullable fields, dense positions, no entry pointing at a missing song). Files that exist only in a browser folder do not travel (the phone would delete such a row at its next index); the export reports how many were left out.
- **Provider names** for lyrics are the Android enum names (`auden/lyrics/names.ts`); unknown ones decode anyway.
- **Ids:** YouTube ids are 11 characters; a file in a folder is `auden:local:<id>` with `localUri` = `auden-file://<rootId>/<path>`; DJ lines are `auden:clip:*`; Spotify tracks are `spotify:*`. Only YouTube ids and `auden:local:` rows with a real row go to the YouTube API.
- **Rows** keep Room's field names, so a backup round-trips.
- **Not identical on purpose:** the Home shuffle uses a seeded generator on the *local* day (same order all day, not the phone's order); the Spotify library cache is kept until a confirmed account change; playlist moves renumber in place.

## Not ported

- **Downloads to a folder** (Phase 12, step 3): not done. A page cannot write freely to `Music/`, tag files without a WASM muxer/tagger, or fetch YouTube audio past SABR/UMP within the terms of service. What exists instead: M3U8 / `.lrc` / cover export, playing the folder the user already has, and **experimental offline copies** (Settings › Offline copies, off by default): the player's own direct audio stream kept in the browser's private storage (OPFS). It needs the extension to download, not to play. On Chromium, "Save to Folder" also writes the files (audio as served, a `.lrc` and a cover, no embedded tags) into a folder you choose; it has not been run against real YouTube here; the site itself still needs a connection to load (no service worker).
- Last.fm genres in the DJ, the DJ's radio "discoveries" and its commentator mode, the classic mesh and the animated-cover sync of the Android player.

## Known follow-ups and what is unverified

- **Backup safety on the phone:** a backup *made by the web* carries no phone settings unless one was imported first; restoring it on Android would reset them. The planned Android-side guard (refuse or keep the settings when the file has none) is not written.
- **The DJ voice** has not run against the real Fish Audio API, and the Pages Function has not been deployed: whether Pages picks up `web/functions/`, and the WAF rule, are to be checked on a test deployment.
- **Extension against real services:** YouTube Music, Spotify (including the Albums and Artists responses) and Discord were not exercised in the development sessions that built this (no extension, no network); parsers are tested with fixtures and Android's samples. Firefox likewise.
- **Real music files:** the library was exercised with synthetic files in OPFS; real MP3/FLAC tags and covers, Chromium's real permission re-prompt and Media Session with `blob:` covers are unchecked.
- Details for each phase, including decisions and gaps, are in the status notes in `docs/AUDEN_WEB_PLAN.md`.

## Naming

Identifiers that are still BitChord's on purpose: the package scope `@bitchord/*`, the `BITCHORD_*` build variables, the extension zips' names, a few `localStorage` keys (`bitchord:*`). Renaming them would change URLs and break stored data or installed extensions for no gain.
