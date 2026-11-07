import { record, type Pending, type SongInfo } from './bucket';

/**
 * ListeningRecorder.kt as a state machine, with the clock and the sink handed in so it can be tested.
 *
 * Minutes accumulate every sample; a PLAY is counted once, when enough of the track has gone by:
 * half of it, never more than four minutes, never less than thirty seconds (the scrobbler's rule).
 * The first sample of a track only anchors the clock. Steps are capped at [MAX_STEP_MS], so an hour
 * of background throttling cannot arrive as an hour of listening.
 *
 * One deliberate difference from the phone: a pause only drops the anchor. The phone resets the
 * track's progress on every pause, so a track paused and resumed could count twice; here it counts
 * once however often it is paused.
 */
export const MAX_STEP_MS = 8_000;
export const PLAY_FLOOR_MS = 30_000;
export const PLAY_CEILING_MS = 4 * 60 * 1000;

export class PlayRecorder {
  pending: Pending = new Map();
  private currentId: string | null = null;
  private anchor: number | null = null;
  private playedThisTrack = 0;
  private playCounted = false;

  /** One tick while [song] is audibly playing. [durationMs] is the decoder's length, or 0 if unknown. */
  onSample(song: SongInfo, durationMs: number, nowMs: number, wall: Date): void {
    if (song.videoId !== this.currentId) {
      this.currentId = song.videoId;
      this.anchor = nowMs;
      this.playedThisTrack = 0;
      this.playCounted = false;
      return;
    }
    if (this.anchor === null) {
      this.anchor = nowMs;
      return;
    }
    const step = Math.min(Math.max(nowMs - this.anchor, 0), MAX_STEP_MS);
    this.anchor = nowMs;
    if (step <= 0) return;
    this.playedThisTrack += step;

    const threshold = durationMs > 0 ? Math.max(Math.min(durationMs / 2, PLAY_CEILING_MS), PLAY_FLOOR_MS) : PLAY_FLOOR_MS;
    const counts = !this.playCounted && this.playedThisTrack >= threshold;
    if (counts) this.playCounted = true;
    record(this.pending, song, step, counts, wall);
  }

  /** Paused or buffering: forget the clock, keep the track's progress. */
  onPause(): void {
    this.anchor = null;
  }

  /** The track ended, or playback stopped: the next sample starts a fresh play (repeat-one included). Idempotent. */
  onTrackEnded(): void {
    this.currentId = null;
    this.anchor = null;
    this.playedThisTrack = 0;
    this.playCounted = false;
  }

  /** Hands over what has been collected and starts a new collection. */
  take(): Pending {
    const taken = this.pending;
    this.pending = new Map();
    return taken;
  }
}
