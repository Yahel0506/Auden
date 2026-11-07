// @vitest-environment happy-dom
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { create } from 'zustand';

type Hooks = { shouldDeferPlay: () => boolean; onLockedTransport: (playing: boolean) => boolean; onIntent: () => void };
let mirrored = false;
let locked = true;
const hooks: { current: Hooks | null } = { current: null };
const calls: string[] = [];

const player = create<{ queue: unknown[]; index: number; status: string }>()(() => ({ queue: [], index: 0, status: 'paused' }));
const partyStore = create<Record<string, unknown>>()(() => ({}));

vi.mock('@/state/player', () => ({
  usePlayer: player,
  getAudio: () => ({ paused: true, currentTime: 12, duration: 200 }),
  isMirrored: () => mirrored,
  setPartyHooks: (h: Hooks | null) => (hooks.current = h),
  partyPlayer: {
    play: () => calls.push('play'),
    pause: () => calls.push('pause'),
    seekTo: () => calls.push('seek'),
    setUpcoming: () => calls.push('setUpcoming'),
    load: () => calls.push('load'),
  },
}));
vi.mock('./listenTogether', () => ({
  useParty: partyStore,
  controlsLocked: () => locked,
  msUntilStart: () => 0,
  partyPositionMs: () => 5000,
  party: new Proxy({}, { get: () => () => calls.push('party') }),
}));

const { startPartySync } = await import('./partySync');

const entry = (videoId: string, source?: 'local' | 'clip') => ({ entryId: videoId, tier: 'user', source: null, track: { videoId, source } });
const live = { code: 'ABCD', connection: 'live', clockSynced: true, playback: { isPlaying: false, seq: 0 }, queue: { items: [], seq: 0 }, you: { isHost: true } };

describe('party hooks while something that is not shareable is in front', () => {
  let stop: () => void;
  beforeEach(() => {
    calls.length = 0;
    partyStore.setState(live, true);
    stop = startPartySync();
  });
  afterEach(() => stop());

  it('does not hold a play back for the party, nor lock the transport, for a local file', () => {
    player.setState({ queue: [entry('auden:local:1', 'local')], index: 0, status: 'paused' });
    expect(hooks.current!.shouldDeferPlay()).toBe(false);
    expect(hooks.current!.onLockedTransport(true)).toBe(false);
    expect(hooks.current!.onLockedTransport(false)).toBe(false);
    expect(calls).not.toContain('seek');
    expect(calls).not.toContain('pause');
  });

  it('does for a YouTube track: a locked listener\'s pause is the party\'s business', () => {
    player.setState({ queue: [entry('abcdefghijk')], index: 0, status: 'playing' });
    expect(hooks.current!.onLockedTransport(false)).toBe(true);
    expect(calls).toContain('pause');
  });

  it('a clip in front is treated like a local file', () => {
    player.setState({ queue: [entry('auden:clip:1', 'clip')], index: 0, status: 'paused' });
    expect(hooks.current!.shouldDeferPlay()).toBe(false);
    expect(hooks.current!.onLockedTransport(true)).toBe(false);
  });

  describe('with a Connect mirror', () => {
    afterEach(() => {
      mirrored = false;
      locked = true;
      vi.useRealTimers();
    });

    const intent = () => {
      vi.useFakeTimers();
      locked = false;
      const track = { videoId: 'abcdefghijk', title: 't', artists: [], album: null, durationSec: 100, thumbnail: null };
      player.setState({ queue: [{ ...entry('abcdefghijk'), track }], index: 0, status: 'playing' });
      hooks.current!.onIntent();
      vi.advanceTimersByTime(500);
    };

    it('publishes the player to the party as it always did', () => {
      intent();
      expect(calls).toContain('party');
    });

    it('never publishes a mirrored session (another device\'s) to the party', () => {
      mirrored = true;
      intent();
      expect(calls).not.toContain('party');
    });
  });
});
