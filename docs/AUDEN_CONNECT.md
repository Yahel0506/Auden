# Auden Connect

Connect lets one signed-in account's own devices hand playback to each other, the way Spotify Connect does: the
audio output menu lists every device of the account that has Auden open and a tap plays there. The rule is an
exact mirror: while something plays on one device and Auden is open on another, the playback and the state on both
are the same (queue, order, song, position, play/pause, shuffle, repeat, volume) and only the output device
differs. The device that does not make the sound shows the session in its normal player and drives it.

It is two halves of one protocol: the web player and the Cloudflare Worker (`web/connect`, `web/shared/src/connect`,
`web/app/src/auden/connect`) and the Android client (`app/.../data/connect`, `app/.../playback/ConnectSync.kt`).

## Architecture

```
 phone / TV (Android)                      Cloudflare Worker                     browser
 ConnectSync  <->  ConnectManager  <->  ConnectClient ==WebSocket==  Durable Object (one per room)  == ConnectClient <-> session.ts
 (ExoPlayer)       (the logic)         (OkHttp)         wss://auden-connect.yahelloppez.workers.dev/ws/<room>
```

* **Room** = the 32-hex account id hash. Android: `sha256("${accountId}:${profileId}").take(32)`
  (`ListenTogether.accountRoomId()`, the same hash Listen Together calls userId). Web: `sha256("${authUser}:${id}").slice(0,32)`.
  Both land in one room.
* **Active device**: the one making sound. The room holds exactly one (or none) and the last `PlaybackState` it published.
  Every other device is a **remote**: its own player is silent and it shows, and drives, the active one.
* The server logic is pure (`room.ts`): hello, claim, transfer, state, command, close, tick. The clients never talk to each other.

## Mirror mode (Android)

While another device is the active one, `ConnectManager.mirroring` is true (`isRemote` and the user has not started something
of their own here) and the phone's normal player shows that session as if it played it, silent.

The model is the one Cast and the watch speaker already use (`RemoteSpeaker`): **the ExoPlayer owns the queue, a speaker owns the clock.**

```
 remote session (RemoteSession: state + server stamp)
        |  ConnectManager.remote / mirroring  (single source of truth; commands update it optimistically)
        v
 ConnectSync.mirror()  --MirrorHydrator-->  idle ExoPlayer: the queue as MediaItems, never prepared (no stream is fetched),
        |                                    playWhenReady=false, STATE_IDLE
        |  ConnectSpeaker (a 3rd RemoteSpeaker in Speakers)  -> SessionPlayer reports play state, position (server clock
        v                                    extrapolation, `playheadMs`), duration, volume, "remote" device info
 media notification / lock screen / headset / Bluetooth / Auto / widgets / watch / app UI   (all read the SessionPlayer)
        ^
        |  SessionPlayer overrides -> MirrorDoor (MirrorRouting) -> ConnectManager.remoteCommand -> `command` frames
 user controls (play pause seek next previous jump repeat shuffle, queue add/remove/move/clear, volume keys)
```

* **Why this shape**: every surface already reads the session player, and that player already knows how to answer from a speaker
  (Cast). Mirror mode is one more speaker plus a door in front of the user's controls, instead of a second player or a UI-only
  overlay. The raw ExoPlayer is idle, so everything keyed to it (scrobble, history, listening record, Discord, ListenBrainz, read-ahead,
  Automix/crossfade, party, loudness) has nothing to react to; the few that react to queue/track *events* are behind `MirrorGate`.
* **Hydration** (`MirrorHydrator` + `MirrorPlan.diff`): on every session change the idle player's queue is brought to the session's with the
  smallest replacement (common head and tail kept, so the playing row and on-screen rows are not touched by an edit elsewhere),
  then `seekTo(index)` if the song differs, then repeat and shuffle. A heartbeat (same queue, same song) writes nothing.
  Writes go straight to the ExoPlayer, below the session wrapper, so they are never mistaken for user intent.
* **Controls** (`SessionPlayer` -> `MirrorDoor`): play/pause/stop/setPlayWhenReady, seekTo/seekBack/seekForward/seekToDefaultPosition, next/previous
  (`seekToNext*`, `seekToPrevious*`), a queue tap (`seekTo(index)` -> `jump`), `setRepeatMode`, the shuffle button (custom session command),
  `addMediaItems` (right behind the playing song = `playNext`, further = `addToQueue`), `removeMediaItem(s)` (`remove`, or `clearUpcoming` for the whole
  tail), `moveMediaItem` (`move`), the hardware volume keys (`DeviceInfo` is REMOTE, as with Cast, so they send `volume`), the sleep timer (pauses the
  other device). `prepare()` and in-place `replaceMediaItems` are refused. The seek/play commands are added to the available commands (an unprepared
  queue would otherwise have none seekable).
* **Optimistic**: `remoteCommand` applies `PlaybackState.afterCommand` to the session at once (next/jump/edit/seek/pause...), the active device's next
  state replaces it. The ExoPlayer follows through hydration, so the UI has one path.
* **Leaving the mirror** (`ConnectManager.leaveMirror`, called by `MirrorDoor.newSession`): starting something new here - `setMediaItems` (play a
  song/album/playlist/search result/the DJ), `clearMediaItems`, a bounded-context/station/radio custom command - joining a party, or connecting a Cast
  receiver / the watch as speaker. The player is then the phone's own; the existing claim (200 ms later, only when the phone is actually playing something
  shareable, not a party queue) makes it the active device and releases the other one. If nothing can be claimed (local file, DJ clip, party) the other device
  keeps playing and the phone stays out of the mirror until the room's active device changes.
* **Output sheet**: it lists devices instantly (unchanged). Picking this phone's row, or any local output, while mirroring does `transfer` to this
  device: the room sends `takeover` with the queue, index, extrapolated position, `order` and the play state, the phone loads it, prepares and plays, and the
  mirror ends. Picking another device sends the usual `state`+`transfer` (when this phone is the active one) or `transfer` (when it mirrors).
  A release turns the phone into a mirror of what it was playing: the queue is already in the player, so hydration changes nothing.
* **Shuffle order**: Android shuffles by reordering the queue. While shuffle is on the snapshot carries `order` (videoIds of the unshuffled order,
  from `QueueShuffle`'s remembered keys, cut to the published window; songs queued after the shuffle go at the end). On a takeover or while mirroring,
  `QueueShuffle.adoptShared` turns `order` into the canonical context (and `original`), so shuffle off - on the phone or by a `shuffle:false` command -
  restores the same list as on every device. Without an `order` (a shuffle carried in without one) only the flag follows.
* **Active side**: every queue edit, reorder, shuffle toggle (the `QueueShuffle.enabled` flow is watched as well) and AutoPlay top-up changes the timeline
  and publishes within the 120 ms debounce. Commands from remotes (`playNext addToQueue remove move clearUpcoming`) are applied to the ExoPlayer below the
  session wrapper (never reaching a party; a party queue ignores them), rows addressed through the last published window.

## Protocol summary (`protocol.ts` is the source of truth; v1)

All JSON is camelCase with a `type` field; optional fields may be absent. First frame must be
`{"type":"hello","v":1,"device":{"deviceId","name","platform":"web|android|tv|watch|pc"}}`. The keep-alive is the plain text
frame `ping`, answered by the plain text frame `pong`, every 25 s; 70 s without any frame and the socket is dead.
Native apps send no `Origin` header.

| client -> server | meaning |
|---|---|
| `hello` | first frame |
| `state {state}` | the active device's playback state (debounced 120 ms on change, every 3 s while playing) |
| `claim {state}` | "I am starting playback here": become active, previous one is released |
| `transfer {to, play}` | hand playback to `to` |
| `command {to?, command}` | remote control (`play pause toggle next previous seek volume shuffle repeat jump`) and queue edits (`playNext addToQueue remove move clearUpcoming`) |

| server -> client | meaning |
|---|---|
| `welcome` | devices, active id, last state and the server stamp of it |
| `devices` | the device list changed |
| `state {from, state}` | the active device's state (relayed) |
| `command {from, command}` | a remote's command for the active device |
| `takeover {state?, play}` | you are active now: load the state, sound when `play` |
| `release {to?}` | you are no longer active: stop, show `to` as a remote |
| `pong`, `error {code}` | |

`PlaybackState` = `{queue (<=100 ConnectTrack), index, positionMs, isPlaying, volume 0..1, shuffle, repeat off|all|one, order?}`;
`order` = the queue's pre-shuffle order as videoIds, present only while shuffle is on (the parser drops it when shuffle is off);
`Command` = `{action, value?, to?, tracks?}`: `value` is ms / 0..1 / queue index / boolean / repeat mode, `move` = `value` (from) and `to`,
`playNext` / `addToQueue` carry `tracks`;
`ConnectTrack` = `{videoId, title, artist, album?, artworkUrl?, durationSec?}`. The remote playhead is
`positionMs + (isPlaying ? serverNow - stateServerMs : 0)`, capped at the duration (`playheadMs`).

## How the halves map

| web | Android |
|---|---|
| `protocol.ts` | `ConnectProtocol.kt` (`@Serializable` sealed messages, `ConnectCodec`, `playheadMs`, `trimmedForWire`) |
| `client.ts` | `ConnectClient.kt` (OkHttp WebSocket, hello, ping/pong, 70 s dead detection, backoff 1/2/4/8/15/30 s +-20%, reconnect on network regain, stops on `protocol`/`too_many_devices`) |
| `session.ts` | `ConnectManager.kt` (claim, takeover, release, command, switchTo, remoteCommand with optimistic update, debounce, heartbeat, the mirror flag) + `ConnectEdits.kt` (optimistic edits, queue diff, shuffle order: pure) |
| `usePlayer` / `partyPlayer` | `ConnectSync.kt` (ExoPlayer snapshot and apply, mirror hydration; modelled on `PartySync`) + `ConnectMirror.kt` (speaker, routing, gate) |
| `DevicePicker` / `RemoteBar` | `AudioOutputSheet` > `ConnectSection.kt` (device rows only: the remote card is gone, the real player is the remote) |
| Settings toggle (`auden:connect`) | `AppSettings.connectEnabled` ("Play on my other devices", default on) |

Server URL: `BuildConfig.CONNECT_SERVER` (default `https://auden-connect.yahelloppez.workers.dev`, set in `app/` and `tv/`
`build.gradle.kts`, overridable with `CONNECT_SERVER` in `local.properties` or the environment). Device name `Build.MODEL`;
platform `BuildConfig.CONNECT_PLATFORM` (`android` for the phone build, `tv` for the TV build). Device id: the install id Listen Together already keeps.

## Decisions

* **Only a user can claim.** The web claims on any status change. Android claims only (a) from the player's user-action door
  (`onUserIntent`, the same door PartySync uses for outbound intent; 200 ms later, so the queue a gesture builds is complete)
  or (b) at connect when the phone is already playing. A release pauses the player and nothing the player then does by itself
  (audio focus back, a restored queue) brings the claim back: a remote session stays until the user presses play on the phone.
* **Inbound writes go under the session wrapper** (takeover, release pause), so they are never mistaken for user intent and
  never reach a party. Remote commands go *through* the session player so skip/seek get the crossfade handling of a button press.
* **Shareable songs**: bare 11-char YouTube ids only. Local files, other sources' prefixed keys and DJ clips are skipped; if the
  current song is not shareable nothing is claimed or handed over. A Listen Together queue is never claimed.
* **Queue window**: 10 behind, up to 89 ahead (<= 100), texts cut to 300, artwork URL dropped when over 600 or not http.
* **Takeover queue tier**: `CONTEXT` (so repeat-all loops it and autoplay continues at the end).
* **Volume** is the music stream volume (0..1 of max), set by a remote's volume command and by takeover.
* **Shuffle**: Android shuffles by physically reordering the queue (`QueueShuffle`). The published queue is already in that order and
  `order` carries the unshuffled one (see Mirror mode), so shuffle off after a takeover restores the sender's list. A remote's shuffle command
  toggles `QueueShuffle` when it differs.
* **The socket lives with the playback service** (`ConnectSync` is started where `PartySync` is). The app's controller keeps the
  service alive while the app is open and while playing; a phone whose service was stopped by idling is simply offline in the list.
* **UI model**: `PlayerHost.connect` exposes a plain `ConnectUi` (devices online) so sharedUi knows nothing of sockets. Hidden when
  Connect is off, signed out or without a room. The old remote card was removed: the mirrored player is the remote.
* **Defaults chosen** (the brief left them open): a queue tap in the mirror is `jump` + `play`; "back" always sends `previous` and the active
  device decides restart-vs-previous; `playNext` lands right behind the playing song and `addToQueue` behind the user's queued songs (as on this
  phone); `clearUpcoming` removes everything after the playing song; a Clear-queue button on the phone (which clears only user-queued songs) arrives as
  `remove`s; stop (dismissed notification) pauses the other device; the autoplay toggle stays a local setting; mirrored rows are all tier CONTEXT
  (the wire has no tiers), so the queue sheet shows them as one list.
* **Mirror needs the service**: the mirror lives in `PlaybackService`; a phone whose service is stopped is offline in the list, not a mirror.
* **Strings**: English and Spanish are translated; the settings strings are copied in English into the other locales (the key-completeness
  test requires every key), and the drawer strings fall back to English where a locale has none.

## Limits

* Identity is an **unverified account hash**, like Listen Together: the room id is unguessable but the server verifies no Google credential. Whoever knows the hash is in the room.
* Devices per room: **8** (a ninth is refused unless an offline one can be forgotten). Messages: 96 KB, queue 100.
* The room keeps a session for **20 s** after the active device goes offline, then lets it go (state published paused, active none).
* The active device re-states every 3 s while playing; a remote's bar interpolates between states.
* A mirror shows at most the published window (10 behind, 89 ahead of the playing song); edits address rows of that window.
* Non-shareable rows (local files, other sources, DJ clips) are not carried, so a mirror never shows them and an edit of one is dropped.
* Casting or the watch as speaker while mirroring ends the mirror without claiming (the other device keeps playing); use the device list.
* A queue edit made on the phone and a conflicting one made on the other device at the same moment resolve to whichever the active device applies last.
* Dragging a queue row sends one `move` per row crossed; the active device applies them in order and publishes once after the burst.

## Not verified

* **No device test**: nothing was installed or run on any phone, TV or watch, and no adb was used. Mirror mode in particular is **compiled and unit-tested
  but never run**: that an unprepared ExoPlayer holding the queue gives a correct notification / lock-screen / Auto / watch / widget, that the added
  seek commands light the seek bar, that `stop()` keeps queue and position on the pinned Media3, and that the hardware volume keys reach the other device
  are all by reading Media3 and the Cast path, not by observation.
* What the JVM tests prove for mirror mode: protocol golden JSON for `order` and the five new commands; the manager's mirror flag, exits and optimistic
  edits; the hydration plan against a fake player; every control and queue edit mapped to its command; the speaker's numbers; the order publish/adopt
  (`QueueShuffle`); and, by reading `PlaybackService.kt`, that every gated side effect asks `MirrorGate` and every session-player control is routed first
  (`ConnectMirrorWiringTest`; mutations were run on each).
* **No real two-device run** against the deployed Worker; the web half was only exercised by its own tests.
* Verified by Gradle only: protocol golden JSON, manager semantics with a fake socket and fake player, the real OkHttp client against a local WebSocket server,
  compile of `:app` and `:tv`. ExoPlayer apply paths (`ConnectSync`), the sheet UI and the Settings row are compiled, not exercised.
* `LocaleStringsTest[ko]` already failed before this work (174 keys missing from `values-ko`); it is unrelated.
* The wear module does not use Connect (no `watch` platform client was written).

## Manual test plan (phone + web, same account)

1. Sign in with the same Google account on the phone build and on the web player; Settings > Play on my other devices is on (both).
2. Web: open the audio output button. The phone is listed as online; on the phone, the output sheet lists "Auden devices" with the browser.
3. **Play on web, see it on the phone**: start an album on the web. Without touching the phone: the mini player shows the same song, the bar moves, the
   now-playing screen and its lyrics follow the web's playhead, the queue shows the same list, the media notification and lock screen show it as playing,
   and the phone makes no sound (also check `adb`-free: no data use, no audio focus taken - the phone's music apps are not ducked).
4. **Control from the phone**: pause/play, next, previous (after 5 s it restarts), drag the seek bar, tap another queue row, toggle repeat, press the
   hardware volume keys, use the headset/Bluetooth play/pause: each acts on the web within a second. A swipe on a search result: Play next / Add to queue
   put it right behind / after the queued songs on both. Remove a row, drag one to a new place, clear the queue: same list on both.
5. **Shuffle**: with the web playing a 20-song album, turn shuffle on from the phone, then off: the list on both returns to the album order. Turn it on
   on the web, off on the phone. Then transfer to the phone while shuffled and turn shuffle off there: the album order again (the old limitation).
6. **Transfer both ways**: output sheet > the phone's row while mirroring: it starts sounding at the same second, the web becomes the mirror (silent,
   same queue). Output sheet > the browser row: back. Repeat with the phone paused.
7. **Start something new on the phone while the web plays**: play any song/playlist/search result, or open the DJ, or join a party: the phone plays it, the web
   goes quiet and mirrors the phone. A local file or a DJ clip: the web keeps playing and the phone does not mirror until the active device changes.
8. **Kill the app** (swipe it away, then open it): the phone rejoins and mirrors the web again. Kill the web tab: after ~20 s the phone keeps the last
   queue paused at the last position as an ordinary queue.
9. **Airplane mode** on the phone for 10 s while mirroring: the mirror holds the last state; on return it re-syncs without sound. For > 25 s while the
   phone is the active one: the web shows nothing active, and the phone claims again when playing resumes.
10. Turn the setting off on the phone: no device list, no mirror (the queue stays as a normal paused queue). Sign out: same.
11. Check scrobbling/history on the phone while the web plays: nothing is recorded by the phone, and AutoPlay does not add songs from the phone.
12. Build with `CONNECT_SERVER` pointing to a staging Worker to test without touching the deployed room.
