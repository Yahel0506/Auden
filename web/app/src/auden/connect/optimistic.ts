import { MAX_QUEUE, playheadMs, type Command, type ConnectTrack, type PlaybackState } from '@bitchord/shared/connect';

/** A session as a remote holds it: the state, and the server time it was true. */
export interface Held {
  state: PlaybackState;
  atServerMs: number;
}

/** A previous that is pressed this far into a song starts it again instead of going back (the player's BACK_RESTARTS_AFTER_SEC). */
const RESTARTS_AFTER_MS = 3_000;

const positionOf = (held: Held, serverNowMs: number): number => {
  const track = held.state.queue[held.state.index];
  return playheadMs(held.state, held.atServerMs, serverNowMs, track?.durationSec ? track.durationSec * 1000 : undefined);
};

/** [queue] with its upcoming part (after [index]) in the order [order] names; songs it does not name keep their place at the end. */
export function reorderUpcoming(queue: readonly ConnectTrack[], index: number, order: readonly string[]): ConnectTrack[] {
  const free = new Map<string, number[]>();
  order.forEach((id, i) => free.set(id, [...(free.get(id) ?? []), i]));
  const upcoming = queue.slice(index + 1).map((track, i) => ({ track, at: free.get(track.videoId)?.shift() ?? order.length + i }));
  return [...queue.slice(0, index + 1), ...upcoming.sort((a, b) => a.at - b.at).map((x) => x.track)];
}

/**
 * What the screen shows the moment a control is pressed on a mirror, before the playing device answers: [command]
 * applied to what is held, the way that device would apply it. Its next state replaces this either way, so a guess
 * that is a little off lasts a moment. Anything it does not understand is left as it was.
 */
export function applyOptimistic(held: Held, command: Command, serverNowMs: number): Held {
  const { state } = held;
  const at = held.atServerMs;
  const here = positionOf(held, serverNowMs);
  const moved = (patch: Partial<PlaybackState>, position = here): Held => ({ state: { ...state, ...patch, positionMs: Math.round(position) }, atServerMs: serverNowMs });
  const edited = (queue: ConnectTrack[], index: number, order: string[] | undefined): Held => ({
    state: { ...state, queue, index, positionMs: Math.round(here), ...(order ? { order } : {}) },
    atServerMs: serverNowMs,
  });
  const withOrder = (queue: ConnectTrack[]): string[] | undefined => (state.order ? state.order.filter((id) => queue.some((t) => t.videoId === id)) : undefined);
  const { queue, index } = state;

  switch (command.action) {
    case 'play':
      return moved({ isPlaying: true });
    case 'pause':
      return moved({ isPlaying: false });
    case 'toggle':
      return moved({ isPlaying: !state.isPlaying });
    case 'seek':
      return typeof command.value === 'number' ? moved({}, command.value) : held;
    case 'volume':
      return typeof command.value === 'number' ? { state: { ...state, volume: Math.min(1, Math.max(0, command.value)) }, atServerMs: at } : held;
    case 'repeat':
      return command.value === 'off' || command.value === 'all' || command.value === 'one' ? { state: { ...state, repeat: command.value }, atServerMs: at } : held;
    case 'shuffle': {
      if (typeof command.value !== 'boolean' || command.value === state.shuffle) return held;
      if (command.value) return { state: { ...state, shuffle: true }, atServerMs: at };
      // Off lands on the order the list had before: the same one every device holds.
      const { order, ...rest } = state;
      return { state: { ...rest, shuffle: false, queue: order ? reorderUpcoming(queue, index, order) : queue }, atServerMs: at };
    }
    case 'next':
      if (index + 1 < queue.length) return moved({ index: index + 1 }, 0);
      return state.repeat !== 'off' && queue.length ? moved({ index: 0 }, 0) : held;
    case 'previous':
      if (here > RESTARTS_AFTER_MS) return moved({}, 0);
      if (index > 0) return moved({ index: index - 1 }, 0);
      return state.repeat === 'all' && queue.length > 1 ? moved({ index: queue.length - 1 }, 0) : moved({}, 0);
    case 'jump': {
      const to = command.value;
      return typeof to === 'number' && to !== index && to >= 0 && to < queue.length ? moved({ index: to, isPlaying: true }, 0) : held;
    }
    case 'playNext':
    case 'addToQueue': {
      const tracks = command.tracks ?? [];
      if (!tracks.length || index < 0) return held;
      const at2 = command.action === 'playNext' ? index + 1 : queue.length;
      const next = [...queue.slice(0, at2), ...tracks, ...queue.slice(at2)].slice(0, MAX_QUEUE);
      return edited(next, index, state.order ? [...state.order, ...tracks.map((t) => t.videoId)] : undefined);
    }
    case 'remove': {
      const i = command.value;
      if (typeof i !== 'number' || i === index || i < 0 || i >= queue.length) return held;
      const next = queue.filter((_, k) => k !== i);
      return edited(next, i < index ? index - 1 : index, withOrder(next));
    }
    case 'move': {
      const from = command.value;
      const to = command.to;
      if (typeof from !== 'number' || typeof to !== 'number') return held;
      if (from <= index || to <= index || from >= queue.length || to >= queue.length || from === to) return held;
      const next = [...queue];
      const [entry] = next.splice(from, 1);
      next.splice(to, 0, entry!);
      return edited(next, index, state.order);
    }
    case 'clearUpcoming': {
      const next = queue.slice(0, index + 1);
      return edited(next, index, withOrder(next));
    }
  }
}
