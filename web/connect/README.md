# Auden Connect (server)

The room server behind **Audio output › Connect to a device**: a signed-in account's own devices
(the web player, the phone, the TV) hand playback to each other and drive each other, as Spotify
Connect does.

A Cloudflare Worker plus one Durable Object per account (SQLite-backed, WebSocket Hibernation). The
protocol is in `web/shared/src/connect/protocol.ts`, the room's rules (pure functions, unit-tested)
in `web/shared/src/connect/room.ts`; this package is only the sockets and the storage around them.

```bash
pnpm --filter @bitchord/connect dev       # wrangler dev on :8787
pnpm --filter @bitchord/connect deploy    # wrangler deploy
```

## How it works

- Each device opens `wss://<host>/ws/<room>` and says `hello`. `<room>` is the account's 32-hex id
  hash, the same `userId` Listen Together uses, so the web and the phone land in one room.
- The room knows who is online, which device is **active** (the one making sound) and the last
  playback state the active device published (queue, index, position, play/pause, volume, shuffle,
  repeat, and while shuffle is on `order`: the queue as it was before the shuffle, as videoIds, so any
  device can turn shuffle off and land on the same list). Every other device is a **mirror**: it shows
  that state as if it were playing it (the web player's own bar, full-screen player, Up Next and lyrics,
  with no sound) and sends commands to the active one.
- `command`: `play`, `pause`, `toggle`, `next`, `previous`, `seek`, `volume`, `shuffle`, `repeat`, `jump`, and the
  queue edits `playNext`, `addToQueue` (with `tracks`), `remove` (index), `move` (from, `to`) and `clearUpcoming`.
  The active device applies one and publishes the new state; every device then shows that one. A mirror also
  answers at once with its own guess (`web/app/src/auden/connect/optimistic.ts`), which the next state replaces.
- `claim`: a device that starts playing becomes active and the previous one is released (pauses). A mirror
  that starts something of its own (a list, a song, the DJ, a party, a local file) claims the session too.
- `transfer`: hand playback to a chosen device, from the position it was at, playing or paused.
- The active device may drop for 20 s without the session ending (a tunnel, a reload).
- A plain text `ping` frame is answered with `pong` by the runtime without waking the object.

## What it is worth (identity)

The room id is a hash of the account and profile ids, not a secret. Like the Listen Together server,
this one cannot verify that a device really is signed in to that account: it holds no Google
credential. Whoever knows the hash can join the room and drive it. The hash is 128 bits of a SHA-256
of two ids nobody publishes, so it cannot be guessed, but it is not authenticated. If that stops
being acceptable, verify a short-lived proof in `ConnectRoom.webSocketMessage` on `hello`.

## Limits

8 devices per room, 100 songs per queue, 96 KB per frame (`protocol.ts`). Browsers must be in
`ALLOWED_ORIGINS` (`wrangler.toml`); native apps send no `Origin`.

## Tested here

`web/shared/src/connect/room.test.ts` (the rules), the web glue in
`web/app/src/auden/connect/{session,mirror,optimistic}.test.ts` (the last two run the real player store),
and a two-device run against `wrangler dev` and the deployed Worker (claim, command, transfer, release,
stale device). The mirror was also driven in a browser against `wrangler dev` with a script as the phone
(`wrangler dev --var ALLOWED_ORIGINS:http://localhost:<port>` lets a dev server's origin in). Not tested: a
real phone with a real web session, and a takeover that actually plays (that needs the extension).
