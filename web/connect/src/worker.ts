import { DurableObject } from 'cloudflare:workers';
import {
  ACTIVE_GRACE_MS,
  emptyRoom,
  onClose,
  onHello,
  onMessage,
  onTick,
  parseClientMessage,
  type Outbound,
  type RoomState,
  type ServerMessage,
} from '@bitchord/shared/connect';

interface Env {
  ROOMS: DurableObjectNamespace;
  ALLOWED_ORIGINS?: string;
}

const ROOM_ID = /^[0-9a-f]{32}$/;
const KEY = 'room';

/** One account's room: sockets in, the pure steps of shared/src/connect/room.ts, frames out. */
export class ConnectRoom extends DurableObject<Env> {
  constructor(ctx: DurableObjectState, env: Env) {
    super(ctx, env);
    // A keep-alive the runtime answers without waking this object.
    ctx.setWebSocketAutoResponse(new WebSocketRequestResponsePair('ping', 'pong'));
  }

  async fetch(request: Request): Promise<Response> {
    if (request.headers.get('Upgrade') !== 'websocket') return new Response('expected a websocket', { status: 426 });
    const pair = new WebSocketPair();
    this.ctx.acceptWebSocket(pair[1]);
    return new Response(null, { status: 101, webSocket: pair[0] });
  }

  async webSocketMessage(ws: WebSocket, data: string | ArrayBuffer): Promise<void> {
    if (typeof data !== 'string') return void ws.close(1003, 'text only');
    const msg = parseClientMessage(data);
    const attached = ws.deserializeAttachment() as { deviceId?: string } | null;
    if (!msg) return this.send(ws, { type: 'error', code: 'bad_message' });
    const room = await this.load();
    const now = Date.now();

    if (msg.type === 'hello') {
      const id = msg.device.deviceId;
      // A second socket for the same device replaces the first (a reload, a reconnect).
      for (const other of this.ctx.getWebSockets()) {
        if (other !== ws && (other.deserializeAttachment() as { deviceId?: string } | null)?.deviceId === id) {
          other.serializeAttachment(null);
          other.close(4000, 'replaced');
        }
      }
      ws.serializeAttachment({ deviceId: id });
      const step = onHello(room, msg, this.online(), now);
      await this.commit(step.room, step.out, now);
      if (step.reject) ws.close(4001, 'rejected');
      return;
    }

    if (!attached?.deviceId) return this.send(ws, { type: 'error', code: 'not_hello' });
    const step = onMessage(room, attached.deviceId, msg, this.online(), now);
    await this.commit(step.room, step.out, now);
  }

  async webSocketClose(ws: WebSocket): Promise<void> {
    const id = (ws.deserializeAttachment() as { deviceId?: string } | null)?.deviceId;
    if (!id) return;
    // [ws] is still listed while this runs: the device is offline unless another socket carries its id.
    const online = this.online(ws);
    const room = await this.load();
    const now = Date.now();
    const step = onClose(room, id, online, now);
    await this.commit(step.room, step.out, now, online);
  }

  async webSocketError(ws: WebSocket): Promise<void> {
    await this.webSocketClose(ws);
  }

  async alarm(): Promise<void> {
    const room = await this.load();
    const now = Date.now();
    const step = onTick(room, this.online(), now);
    await this.commit(step.room, step.out, now);
  }

  private async load(): Promise<RoomState> {
    return (await this.ctx.storage.get<RoomState>(KEY)) ?? emptyRoom();
  }

  /** Device ids with an open socket, leaving out [closing]. */
  private online(closing?: WebSocket): Set<string> {
    const ids = new Set<string>();
    for (const ws of this.ctx.getWebSockets()) {
      if (ws === closing) continue;
      const id = (ws.deserializeAttachment() as { deviceId?: string } | null)?.deviceId;
      if (id) ids.add(id);
    }
    return ids;
  }

  private async commit(room: RoomState, out: Outbound[], now: number, online = this.online()): Promise<void> {
    await this.ctx.storage.put(KEY, room);
    if (room.activeLostAtMs !== null) await this.ctx.storage.setAlarm(room.activeLostAtMs + ACTIVE_GRACE_MS + 250);
    const sockets = this.ctx.getWebSockets();
    for (const o of out) {
      if ('to' in o) {
        for (const ws of sockets) if ((ws.deserializeAttachment() as { deviceId?: string } | null)?.deviceId === o.to && online.has(o.to)) this.send(ws, o.message);
      } else {
        for (const ws of sockets) {
          const id = (ws.deserializeAttachment() as { deviceId?: string } | null)?.deviceId;
          if (id && id !== o.except && online.has(id)) this.send(ws, o.message);
        }
      }
    }
  }

  private send(ws: WebSocket, message: ServerMessage): void {
    try {
      ws.send(JSON.stringify(message));
    } catch {
      // A socket that closed under us: its close event cleans up.
    }
  }
}

function originAllowed(request: Request, env: Env): boolean {
  const origin = request.headers.get('Origin');
  // Native apps send none; a browser must be on the list.
  if (!origin) return true;
  return (env.ALLOWED_ORIGINS ?? '')
    .split(',')
    .map((o) => o.trim())
    .includes(origin);
}

export default {
  async fetch(request: Request, env: Env): Promise<Response> {
    const url = new URL(request.url);
    if (url.pathname === '/healthz') return Response.json({ ok: true, service: 'auden-connect' });
    const match = /^\/ws\/([0-9a-f]{32})$/.exec(url.pathname);
    if (!match || !ROOM_ID.test(match[1]!)) return new Response('not found', { status: 404 });
    if (!originAllowed(request, env)) return new Response('forbidden', { status: 403 });
    return env.ROOMS.get(env.ROOMS.idFromName(match[1]!)).fetch(request);
  },
};
