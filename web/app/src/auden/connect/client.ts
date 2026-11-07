import { CONNECT_PROTOCOL, type ClientMessage, type ConnectDevice, type ServerMessage } from '@bitchord/shared/connect';

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
