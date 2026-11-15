import { CONNECT_PROTOCOL, RoomMerger, type ClientMessage, type ConnectDevice, type ServerMessage } from '@bitchord/shared/connect';

export type ConnectStatus = 'off' | 'connecting' | 'online';

export interface ConnectClientOptions {
  /** https://host of the Connect Worker. */
  url: string;
  /** The account's id hash: the room. */
  room: string;
  device: ConnectDevice;
  onMessage(message: ServerMessage): void;
  onStatus(status: ConnectStatus): void;
}

const KEEPALIVE_MS = 25_000;
/** No frame (not even the keep-alive's answer) for this long: the socket is dead, whatever it says. */
const DEAD_AFTER_MS = 70_000;
const BACKOFF_MS = [1_000, 2_000, 4_000, 8_000, 15_000, 30_000];

/** The socket to the Connect room: opens, says hello, keeps itself alive and reconnects with backoff. */
export class ConnectClient {
  private ws: WebSocket | null = null;
  private stopped = true;
  private attempt = 0;
  private lastFrameAt = 0;
  private timers: ReturnType<typeof setTimeout>[] = [];
  private keepalive: ReturnType<typeof setInterval> | null = null;

  constructor(private readonly options: ConnectClientOptions) {}

  start(): void {
    if (!this.stopped) return;
    this.stopped = false;
    document.addEventListener('visibilitychange', this.onVisible);
    window.addEventListener('online', this.onVisible);
    this.open();
  }

  stop(): void {
    this.stopped = true;
    document.removeEventListener('visibilitychange', this.onVisible);
    window.removeEventListener('online', this.onVisible);
    this.clearTimers();
    const ws = this.ws;
    this.ws = null;
    ws?.close(1000, 'stop');
    this.options.onStatus('off');
  }

  /** False when the socket is not open: the frame is dropped, nothing is queued (the next state replaces it). */
  send(message: ClientMessage): boolean {
    if (this.ws?.readyState !== WebSocket.OPEN) return false;
    this.ws.send(JSON.stringify(message));
    return true;
  }

  private onVisible = () => {
    // A tab that slept or a network that came back: do not wait out the backoff.
    if (this.stopped || document.visibilityState === 'hidden') return;
    if (this.ws?.readyState === WebSocket.OPEN && Date.now() - this.lastFrameAt < DEAD_AFTER_MS) return;
    this.clearTimers();
    this.attempt = 0;
    this.open();
  };

  private open(): void {
    if (this.stopped) return;
    this.ws?.close();
    this.options.onStatus('connecting');
    const socket = new WebSocket(`${this.options.url.replace(/^http/, 'ws')}/ws/${this.options.room}`);
    this.ws = socket;
    socket.onopen = () => {
      if (this.ws !== socket) return;
      this.lastFrameAt = Date.now();
      socket.send(JSON.stringify({ type: 'hello', v: CONNECT_PROTOCOL, device: this.options.device } satisfies ClientMessage));
      this.keepalive = setInterval(() => {
        if (Date.now() - this.lastFrameAt > DEAD_AFTER_MS) socket.close();
        else if (socket.readyState === WebSocket.OPEN) socket.send('ping');
      }, KEEPALIVE_MS);
    };
    socket.onmessage = (event) => {
      if (this.ws !== socket) return;
      this.lastFrameAt = Date.now();
      if (typeof event.data !== 'string' || event.data === 'pong') return;
      let message: ServerMessage;
      try {
        message = JSON.parse(event.data) as ServerMessage;
      } catch {
        return;
      }
      if (message.type === 'welcome') {
        this.attempt = 0;
        this.options.onStatus('online');
      }
      if (message.type === 'error' && (message.code === 'protocol' || message.code === 'too_many_devices')) this.stopped = true;
      this.options.onMessage(message);
    };
    socket.onclose = () => {
      if (this.ws !== socket) return;
      this.ws = null;
      this.clearTimers();
      if (this.stopped) return;
      this.options.onStatus('connecting');
      const wait = BACKOFF_MS[Math.min(this.attempt++, BACKOFF_MS.length - 1)]! * (0.8 + Math.random() * 0.4);
      this.timers.push(setTimeout(() => this.open(), wait));
    };
    socket.onerror = () => socket.close();
  }

  private clearTimers(): void {
    for (const t of this.timers) clearTimeout(t);
    this.timers = [];
    if (this.keepalive) clearInterval(this.keepalive);
    this.keepalive = null;
  }
}

/** What the session needs of its link to the rooms: one room or several. */
export interface ConnectLink {
  start(): void;
  stop(): void;
  send(message: ClientMessage): boolean;
}

/**
 * A device in several rooms at once — its Spotify account's and the one for its network. One socket per room; what
 * comes in is folded into one stream by [RoomMerger] and what goes out is sent to every room that is open (a room
 * that does not hold the target of a command or a transfer ignores it).
 */
export class MultiConnectClient implements ConnectLink {
  private readonly clients: ConnectClient[];
  private readonly statuses: ConnectStatus[];
  private readonly merger: RoomMerger;
  private last: ConnectStatus = 'off';

  constructor(private readonly options: Omit<ConnectClientOptions, 'room'> & { rooms: string[] }) {
    this.merger = new RoomMerger(options.device.deviceId);
    this.statuses = options.rooms.map(() => 'off' as ConnectStatus);
    this.clients = options.rooms.map(
      (room, index) =>
        new ConnectClient({
          url: options.url,
          room,
          device: options.device,
          onMessage: (message) => {
            for (const out of this.merger.feed(index, message)) options.onMessage(out);
          },
          onStatus: (status) => {
            this.statuses[index] = status;
            this.publishStatus();
          },
        }),
    );
  }

  start(): void {
    for (const c of this.clients) c.start();
  }

  stop(): void {
    for (const c of this.clients) c.stop();
  }

  send(message: ClientMessage): boolean {
    let sent = false;
    for (const c of this.clients) sent = c.send(message) || sent;
    return sent;
  }

  private publishStatus(): void {
    const status: ConnectStatus = this.statuses.includes('online') ? 'online' : this.statuses.includes('connecting') ? 'connecting' : 'off';
    if (status === this.last) return;
    this.last = status;
    this.options.onStatus(status);
  }
}
