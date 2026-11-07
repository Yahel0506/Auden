import { useEffect, useRef } from 'react';
import { cancelFrame, requestFrame } from '@/lib/frames';
import { getPlayhead } from '@/state/player';
import { useDj } from '../session';
import {
  ECHOES,
  ECHO_WIDTH,
  GLOW_ALPHA,
  GLOW_WIDTH,
  POINTS,
  SWEEP,
  SWEEP_DEG_PER_SEC,
  echoDrift,
  fillRing,
  geometry,
  smooth,
  talkingLevel,
  voiceTarget,
} from './ringShape';

/**
 * The DJ's ring (Android DjVisuals.kt, DjRing): one thick, clean loop that wobbles as the voice rises, with three echoes
 * of itself trailing in other colours, lit by a sweep of pastel turning round it, on the moving mesh. At rest it
 * breathes. Drawn on ONE canvas 2D: the device pixel ratio is capped at 2, the frame rate at 40 fps (time-based, so the
 * speed is the same), no CSS blur, no allocation per frame, and it stops while the tab is hidden, the ring is off screen
 * or [running] is off. The voice's loudness is the clip's precomputed envelope read at the playhead (as on Android; no
 * AnalyserNode: the Automix graph owns the only media-element source of the decks, and a second one throws).
 *
 * [talking] makes it speak on its own, for where there is no voice to follow (the card on Home).
 */

const FRAME_MS = 1000 / 40;



export function DjRing({ className, running = true, talking = false }: { className?: string; running?: boolean; talking?: boolean }) {
  const canvas = useRef<HTMLCanvasElement>(null);
  const held = useRef({ t: 0, level: 0 });
  const clock = held.current;

  useEffect(() => {
    const el = canvas.current;
    const ctx = el?.getContext('2d');
    if (!el || !ctx || !running) {
      // Still, but drawn: a held ring is the ring at rest, not an empty box.
      if (el && ctx) drawStill(el, ctx, talking, clock.t);
      return;
    }
    const reduce = window.matchMedia?.('(prefers-reduced-motion: reduce)').matches ?? false;
    let raf = 0;
    let last = 0;
    let active = false;
    let onScreen = true;
    let t = clock.t;
    let level = clock.level;
    let dpr = 1;
    const buffer = new Float32Array(2 * (POINTS + 1));

    const resize = () => {
      const rect = el.getBoundingClientRect();
      dpr = Math.min(window.devicePixelRatio || 1, 2);
      el.width = Math.max(1, Math.round(rect.width * dpr));
      el.height = Math.max(1, Math.round(rect.height * dpr));
    };
    const observer = typeof ResizeObserver === 'function' ? new ResizeObserver(resize) : null;
    observer?.observe(el);
    resize();

    const frame = (now: number) => {
      raf = 0;
      if (!active) return;
      if (now - last >= FRAME_MS - 1) {
        const dt = last ? Math.min(now - last, 100) : FRAME_MS;
        last = now;
        // "Reduce animation" holds the clock still (Android's rememberDjClock), so the lobes stop drifting.
        if (!reduce) t += dt / 1000;
        if (talking) {
          level = talkingLevel(t);
        } else {
          const { voice, pending } = useDj.getState();
          const head = voice ? getPlayhead() : null;
          const playing = head?.paused === false;
          const target = voiceTarget({
            loading: pending,
            playing,
            envelope: voice?.envelope,
            positionMs: head ? head.sec * 1000 : 0,
            durationMs: voice?.durationMs ?? 0,
            t,
          });
          level = Math.min(1, Math.max(0, smooth(level, target, dt)));
        }
        clock.t = t;
        clock.level = level;
        draw(ctx, el.width, el.height, dpr, t, level, buffer);
      }
      raf = requestFrame(frame);
    };
    const start = () => {
      if (active || !onScreen || document.visibilityState === 'hidden') return;
      active = true;
      last = 0;
      raf = requestFrame(frame);
    };
    const stop = () => {
      active = false;
      if (raf) cancelFrame(raf);
      raf = 0;
    };
    const onVisibility = () => (document.visibilityState === 'hidden' ? stop() : start());
    document.addEventListener('visibilitychange', onVisibility);
    const seen =
      typeof IntersectionObserver === 'function'
        ? new IntersectionObserver((entries) => {
            onScreen = entries[entries.length - 1]?.isIntersecting ?? true;
            if (onScreen) start();
            else stop();
          })
        : null;
    seen?.observe(el);
    start();
    return () => {
      stop();
      observer?.disconnect();
      seen?.disconnect();
      document.removeEventListener('visibilitychange', onVisibility);
    };
  }, [running, talking]);

  return <canvas ref={canvas} className={className} aria-hidden="true" />;
}

function drawStill(el: HTMLCanvasElement, ctx: CanvasRenderingContext2D, talking: boolean, t: number) {
  const rect = el.getBoundingClientRect();
  const dpr = Math.min(window.devicePixelRatio || 1, 2);
  el.width = Math.max(1, Math.round(rect.width * dpr));
  el.height = Math.max(1, Math.round(rect.height * dpr));
  draw(ctx, el.width, el.height, dpr, t, talking ? talkingLevel(t) : 0, new Float32Array(2 * (POINTS + 1)));
}

function trace(ctx: CanvasRenderingContext2D, points: Float32Array) {
  ctx.beginPath();
  ctx.moveTo(points[0]!, points[1]!);
  for (let i = 1; i <= POINTS; i++) ctx.lineTo(points[2 * i]!, points[2 * i + 1]!);
  ctx.closePath();
}

/** One frame, as Android's Canvas block draws it. Sizes are CSS px, scaled by [dpr]. */
function draw(ctx: CanvasRenderingContext2D, pixelW: number, pixelH: number, dpr: number, t: number, level: number, buffer: Float32Array) {
  const w = pixelW / dpr;
  const h = pixelH / dpr;
  ctx.setTransform(dpr, 0, 0, dpr, 0, 0);
  ctx.clearRect(0, 0, w, h);
  const side = Math.min(w, h);
  const cx = w / 2;
  const cy = h / 2;
  const geo = geometry(side, t, level);
  ctx.lineJoin = 'round';
  ctx.lineCap = 'round';

  // The echoes: the same ring a moment ago, a little off to one side, each in its own colour.
  for (const { lag, color, alpha } of ECHOES) {
    const [dx, dy] = echoDrift(side, t, level, lag);
    fillRing(buffer, cx + dx, cy + dy, geo, t - lag);
    trace(ctx, buffer);
    ctx.globalAlpha = alpha;
    ctx.strokeStyle = color;
    ctx.lineWidth = geo.stroke * ECHO_WIDTH;
    ctx.stroke();
  }
  ctx.globalAlpha = 1;

  // A soft glow under the ring, then the ring itself with colour moving round it.
  fillRing(buffer, cx, cy, geo, t);
  trace(ctx, buffer);
  ctx.strokeStyle = `rgba(255,255,255,${GLOW_ALPHA})`;
  ctx.lineWidth = geo.stroke * GLOW_WIDTH;
  ctx.stroke();

  ctx.save();
  ctx.translate(cx, cy);
  ctx.rotate(((t * SWEEP_DEG_PER_SEC) * Math.PI) / 180);
  ctx.translate(-cx, -cy);
  if (typeof ctx.createConicGradient === 'function') {
    const sweep = ctx.createConicGradient(0, cx, cy);
    SWEEP.forEach((color, i) => sweep.addColorStop(i / (SWEEP.length - 1), color));
    ctx.strokeStyle = sweep;
  } else {
    ctx.strokeStyle = '#ffffff';
  }
  ctx.lineWidth = geo.stroke;
  trace(ctx, buffer);
  ctx.stroke();
  ctx.restore();
}
