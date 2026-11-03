<div align="center">

<br/>

<img src="Logo.png" alt="Auden app icon" width="200" />

# Auden

### A YouTube Music client with a real local library

<br/>

[![Latest release](https://img.shields.io/github/v/release/Yahel0506/Auden?style=for-the-badge&labelColor=0d1117)](https://github.com/Yahel0506/Auden/releases)
[![License](https://img.shields.io/github/license/Yahel0506/Auden?style=for-the-badge&labelColor=0d1117)](LICENSE)
[![Downloads](https://img.shields.io/github/downloads/Yahel0506/Auden/total?style=for-the-badge&labelColor=0d1117)](https://github.com/Yahel0506/Auden/releases)

<br/>

[**Features**](#features) · [**Platforms**](#platforms) · [**Download**](#download) · [**Build**](#build) · [**Contributing**](#contributing) · [**Disclaimer**](#disclaimer)

</div>

> [!IMPORTANT]
> Auden is not affiliated with, endorsed by, or connected to YouTube or Google in any way. Use it at your own discretion.

---

Auden is a fork of [BitChord](https://github.com/kushagrasinghx/BitChord). It keeps BitChord's Apple Music–style player, word-synced lyrics and YouTube Music streaming, and adds the library management of a dedicated local player: an indexed on-device library, local playlists that work without signing in, organised downloads, per-song lyrics control, a hybrid Home, and an AI DJ that talks between songs.

### What Auden adds to BitChord

| Area | BitChord | Auden |
| --- | --- | --- |
| Library | Streaming-first; local files are scanned on demand | Indexed `AudenDb` library with Genres and Folders, local files identified and linked to streamed songs |
| Playlists | Account-based | Local playlists that work signed out, M3U8 / XSPF / JSON import and export, library backup |
| Downloads | One flat file per track | Template-based folders, rich tags, lyrics and `cover.jpg` beside each song, persistent queue with retries, AAC fallback |
| Lyrics | One global provider order and offset | Variants stored per provider, per-song provider and timing, batch lookup, downloads that follow your provider order |
| Spotify | Basic library import | Read-only library, remembered matches, albums and artists, playlists that stay in sync |
| Home | YouTube Music recommendations | Hybrid Home: local shelves mixed with YouTube Music's |
| DJ | — | Auden DJ: themed sets, spoken in Spanish or English, requests that follow your taste |
| Identity | BitChord | Own name, icons and logo; installs next to BitChord and updates from this repository |

<div align="center">

<img src="Banner.png" alt="Auden banner" width="100%" />

</div>

---

<h2><a id="features"></a>Features</h2>

### Local library
- **Indexed library** — an incremental indexer fills `AudenDb` (Room) from MediaStore and your chosen folders, with Genres and Folders tabs.
- **Local playlists, no account needed** — create, edit and reorder playlists; remove songs from device playlists; mosaic covers.
- **Identify what you already own** — match local files to songs, split multi-artist tags, and link streamed songs to files already on the device.
- **Import / export playlists** — M3U8, XSPF and Auden JSON.
- **Backup and restore** the whole local library.

### Downloads
- **Organised folders** from a configurable template, with playlist files for releases.
- **Rich tags** — album artist, track, disc, year and source link written into each file.
- **Lyrics and `cover.jpg` beside each song**, in a folder you can open from any file manager.
- **Persistent queue** with retries and back-off, pause, and adjustable concurrency.
- **AAC fallback** — when YouTube offers a release in AAC only, the track is saved as AAC instead of failing with "No downloadable audio".

### Lyrics
- **Word-synced lyrics** from many providers, stored per provider as variants.
- **Per-song choice and timing** — pick the provider and offset for each song, and manage them from the player's provider sheet.
- **Batch lookup**, sidecar `.lrc` / `.ttml` files and embedded tags for offline use. Searching a playlist's lyrics looks every song up again and rewrites a downloaded track's sidecar and tag.
- **Downloads follow your provider order** and syllable-first setting, and keep the best answer found (word-synced, else the top-priority line-synced) if the lookup runs out of time.

### Auden DJ
- **Themed sets** that start from the Home card, straight into the DJ's own screen, and queue like music. Each set opens with a reason about the listener and the song it begins on, with many phrase variants per situation (the DJ sometimes lets a set run without speaking).
- **Spoken in Spanish or English** through Fish Audio. It needs a `FISH_API_KEY` in a git-ignored `.env` at the repo root (see `.env.example`), `local.properties` or the environment; without one the DJ still plays and its words are on screen. The old on-device voice packs are gone.
- **A DJ player** — the player itself becomes the DJ screen, with a ring on the moving mesh that deforms with the voice. Tap the corner button to type a request (or ask for more, less or stop); long-press it for "surprise me".
- **Requests that follow your taste** — a typed ask is searched in each of your genres and from your favourite artists, and anything the library can't make sense of is searched on YouTube Music. The DJ says when it went by your taste.
- **Its own Settings category** for language, voice, comments and set size. Playing something else by hand ends the DJ and restores shuffle and AutoPlay.

### Home and Spotify
- **Hybrid Home** — local shelves mixed with YouTube Music's recommendations.
- **Spotify import** — read-only library access, remembered matches with manual correction, albums and artists, and saved playlists that keep themselves up to date (additive, never destructive).

### Player
- Gapless playback with crossfade, Automix beat-matching, playback speed, skip silence, sleep timer and system equalizer.
- Artwork-driven Material theming, a full-cover blur backdrop, and an output pill that shows the real output device.
- Video fades in progressively as the cover returns to the player.
- Scrobbling to Last.fm and ListenBrainz, and Discord Rich Presence.

### Identity and updates
- **Auden branding** — an A-waveform logo with Minimal, Liquid Glass Light and Liquid Glass Dark icons.
- **Installs next to BitChord** under its own application ID (`dev.auden.app`), so both can live on one device.
- **Updates from this repository** — releases and the in-app updater follow Auden, and local library backups are named Auden.

---

<h2><a id="platforms"></a>Platforms</h2>

| Target | Module | Notes |
| --- | --- | --- |
| Android phone | [`app/`](app) | The main app (`dev.auden.app`) |
| Android TV / Google TV | [`tv/`](tv) | Remote-first interface, see its [README](tv/README.md) |
| Wear OS | [`wearos/`](wearos) | Watch remote and speaker, see its [README](wearos/README.md) |
| Web + browser extension | [`web/`](web) | Vite + React player, see its [README](web/README.md) |
| Listen Together server | [`backend/`](backend) | Go party server, see its [README](backend/README.md) |

Shared code lives in [`shared/`](shared) and [`sharedUi/`](sharedUi); native audio analysis and FFmpeg are in [`native/`](native).

---

<h2><a id="download"></a>Download</h2>

Grab the latest signed APK from the [Releases](https://github.com/Yahel0506/Auden/releases) page. Sideloading requires enabling "Install unknown apps" for whichever app you download it with. The app checks the Auden repository for updates.

---

<h2><a id="build"></a>Build</h2>

You need JDK 17+ and the Android SDK.

```bash
./gradlew :app:assembleProdDebug     # phone
./gradlew :tv:assembleProdDebug      # Android TV
```

Release builds read signing details from `keystore.properties`; copy [`keystore.properties.example`](keystore.properties.example) and generate a key with [`tools/make-release-keystore.sh`](tools/make-release-keystore.sh). The plan and phase breakdown behind the fork are in [`docs/AUDEN_ROADMAP.md`](docs/AUDEN_ROADMAP.md) (in Spanish).

---

<h2><a id="contributing"></a>Contributing</h2>

Please read the [Contributing Guide](CONTRIBUTING.md) and [Code of Conduct](CODE_OF_CONDUCT.md) before opening a pull request.

[**Contributing Guide**](CONTRIBUTING.md) · [**Code of Conduct**](CODE_OF_CONDUCT.md) · [**Maintainers**](MAINTAINERS.md) · [**Additional Docs**](ADDITIONAL.md)

---

## Credits

Auden stands on [BitChord](https://github.com/kushagrasinghx/BitChord) by kushagrasinghx and its contributors. Lyrics come from community providers including LRCLIB, Musixmatch, Genius, KuGou, Megalobiz, BetterLyrics, PaxSenix and others; the Apple-like lyrics animation is based on [am-lyrics](https://github.com/binimum/am-lyrics).

---

<h2><a id="disclaimer"></a>Disclaimer & Legal Notice</h2>

Auden is an independent, community-driven third-party audio player and client. It is **not** associated with Google LLC, YouTube Music, Spotify or any of their parent companies.

* **No media hosting:** Auden does not host, upload, or store copyrighted music files. It scans local device storage and streams media from public or user-authenticated APIs.
* **Fair use:** This software is for personal research, educational and fair-use purposes. You are responsible for making sure your usage complies with your local copyright laws and the terms of the services you connect to.
* **Copyleft:** Auden is free software under the GPLv3. Any distribution must come with the Corresponding Source under the same license.

## License

Licensed under the **GNU General Public License v3.0 (GPLv3)**. See [LICENSE](LICENSE).
