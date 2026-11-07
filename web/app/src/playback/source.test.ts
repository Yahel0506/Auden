import { afterEach, describe, expect, it, vi } from 'vitest';
import type { Track } from '@/data/music/types';
import {
  CLIP_GONE,
  clipIdOf,
  clipTrackId,
  FILE_UNAVAILABLE,
  isShareable,
  isSyntheticId,
  localRowIdOf,
  localTrackId,
  mixPolicy,
  obtainMedium,
  readMedium,
  resolveMedium,
  sourceOf,
  offlineCopyPlays,
  type MediumContext,
} from './source';

const track = (videoId: string, source?: Track['source']): Track => ({
  videoId,
  title: 'T',
  artists: [],
  album: null,
  durationSec: 100,
  explicit: false,
  thumbnail: null,
  index: null,
  plays: null,
  ...(source ? { source } : {}),
});

const blob = new Blob(['x'], { type: 'audio/mpeg' });
const ctx = (over: Partial<MediumContext> = {}): MediumContext => ({ inParty: false, clip: () => null, local: null, ...over });

describe('identity', () => {
  it('treats a track without a source as YouTube, and only YouTube as shareable', () => {
    expect(sourceOf(track('dQw4w9WgXcQ'))).toBe('youtube');
    expect(isShareable(track('dQw4w9WgXcQ'))).toBe(true);
    expect(isShareable(track(localTrackId(7), 'local'))).toBe(false);
    expect(isShareable(track(clipTrackId('c1'), 'clip'))).toBe(false);
  });

  it('round-trips synthetic ids', () => {
    expect(localTrackId(42)).toBe('auden:local:42');
    expect(localRowIdOf('auden:local:42')).toBe('42');
    expect(clipIdOf(clipTrackId('intro-3'))).toBe('intro-3');
    expect(clipIdOf('auden:local:42')).toBeNull();
    expect(localRowIdOf('dQw4w9WgXcQ')).toBeNull();
    expect(clipIdOf('auden:clip:')).toBeNull();
    expect(isSyntheticId('auden:clip:x')).toBe(true);
    expect(isSyntheticId('dQw4w9WgXcQ')).toBe(false);
  });
});

describe('resolveMedium', () => {
  it('plays a YouTube song from YouTube when there is no file provider', () => {
    expect(resolveMedium(track('abc'), ctx())).toEqual({ kind: 'youtube' });
  });

  it('tries a linked file first, falling back to YouTube', () => {
    const plan = resolveMedium(track('abc'), ctx({ local: async () => blob }));
    expect(plan).toMatchObject({ kind: 'file', fallback: 'youtube' });
  });

  it('ignores linked files in a party', () => {
    const local = vi.fn(async () => blob);
    expect(resolveMedium(track('abc'), ctx({ inParty: true, local }))).toEqual({ kind: 'youtube' });
    expect(local).not.toHaveBeenCalled();
  });

  it('plays a pure local track from its file, with nothing to fall back on', () => {
    expect(resolveMedium(track(localTrackId(1), 'local'), ctx({ local: async () => blob }))).toMatchObject({ kind: 'file', fallback: null });
    expect(resolveMedium(track(localTrackId(1), 'local'), ctx())).toEqual({ kind: 'unavailable', reason: FILE_UNAVAILABLE });
  });

  it('a pure local track still plays from its file in a party', () => {
    expect(resolveMedium(track(localTrackId(1), 'local'), ctx({ inParty: true, local: async () => blob }))).toMatchObject({ kind: 'file' });
  });

  it('plays a clip from the registry, or calls it gone', async () => {
    const plan = resolveMedium(track(clipTrackId('c1'), 'clip'), ctx({ clip: (id) => (id === 'c1' ? blob : null) }));
    expect(plan).toMatchObject({ kind: 'file', fallback: null });
    if (plan.kind === 'file') expect(await plan.getBlob()).toBe(blob);
    expect(resolveMedium(track(clipTrackId('c2'), 'clip'), ctx({ clip: () => null }))).toEqual({ kind: 'unavailable', reason: CLIP_GONE });
  });

  it('stays on the synchronous YouTube path when the probe says no file is linked', () => {
    const local = vi.fn(async () => blob);
    expect(resolveMedium(track('abc'), ctx({ local, hasLink: () => false }))).toEqual({ kind: 'youtube' });
    expect(resolveMedium(track('abc'), ctx({ local, hasLink: () => true }))).toMatchObject({ kind: 'file', fallback: 'youtube' });
    expect(local).not.toHaveBeenCalled();
  });

  it('preferStream plays the stream even when a file is linked (Automix back deck)', () => {
    expect(resolveMedium(track('abc'), ctx({ local: async () => blob, hasLink: () => true, preferStream: true }))).toEqual({ kind: 'youtube' });
  });

  it('never sends a synthetic id to YouTube', () => {
    expect(resolveMedium(track('auden:local:9'), ctx())).toMatchObject({ kind: 'unavailable' });
  });
});

describe('obtainMedium', () => {
  afterEach(() => vi.useRealTimers());

  it('reads the linked file', async () => {
    expect(await obtainMedium(track('abc'), ctx({ local: async () => blob }))).toEqual({ kind: 'file', blob, fallback: 'youtube' });
  });

  it('falls back to YouTube when the linked file is missing or refused', async () => {
    expect(await obtainMedium(track('abc'), ctx({ local: async () => null }))).toMatchObject({ kind: 'youtube', fellBack: 'no file' });
    const refused = await obtainMedium(
      track('abc'),
      ctx({
        local: async () => {
          throw new DOMException('gone', 'NotReadableError');
        },
      }),
    );
    expect(refused).toMatchObject({ kind: 'youtube' });
    expect(refused.kind === 'youtube' && refused.fellBack).toContain('NotReadableError');
  });

  it('falls back to YouTube when the provider hangs', async () => {
    vi.useFakeTimers();
    const pending = obtainMedium(track('abc'), ctx({ local: () => new Promise<Blob | null>(() => {}) }), 1000);
    await vi.advanceTimersByTimeAsync(1000);
    expect(await pending).toMatchObject({ kind: 'youtube' });
  });

  it('calls a pure local track unavailable when its file cannot be read', async () => {
    expect(await obtainMedium(track(localTrackId(3), 'local'), ctx({ local: async () => null }))).toEqual({
      kind: 'unavailable',
      reason: FILE_UNAVAILABLE,
    });
  });

  it('passes a plan with nothing to read straight through', async () => {
    expect(await readMedium({ kind: 'youtube' })).toEqual({ kind: 'youtube' });
  });
});

describe('mixPolicy', () => {
  const yt = {} as const;
  const local = { source: 'local' } as const;
  const clip = { source: 'clip' } as const;
  it('lets Automix mix two YouTube tracks only', () => {
    expect(mixPolicy(yt, yt)).toBe('automix');
    expect(mixPolicy(yt, local)).toBe('crossfade');
    expect(mixPolicy(local, local)).toBe('crossfade');
  });
  it('cuts, never blends, on either side of a clip', () => {
    expect(mixPolicy(yt, clip)).toBe('cut');
    expect(mixPolicy(clip, local)).toBe('cut');
    expect(mixPolicy(clip, clip)).toBe('cut');
  });
});

describe('offline copies (Phase 12)', () => {
  const copy = new Blob(['offline'], { type: 'audio/webm' });
  const offline = (has = true, read: () => Promise<Blob | null> = () => Promise.resolve(copy)) => ({ has: () => has, read });
  const yt = track('dQw4w9WgXcQ');

  it('plays a YouTube song from its copy, falling back to YouTube', async () => {
    const plan = resolveMedium(yt, ctx({ offline: offline() }));
    expect(plan).toMatchObject({ kind: 'file', fallback: 'youtube' });
    expect(await readMedium(plan)).toEqual({ kind: 'file', blob: copy, fallback: 'youtube' });
  });

  it('streams when there is no copy, in a party, when asked to, and while Automix mixes online', () => {
    expect(resolveMedium(yt, ctx({ offline: offline(false) }))).toEqual({ kind: 'youtube' });
    expect(resolveMedium(yt, ctx({ offline: offline(), inParty: true }))).toEqual({ kind: 'youtube' });
    expect(resolveMedium(yt, ctx({ offline: offline(), preferStream: true }))).toEqual({ kind: 'youtube' });
    expect(resolveMedium(yt, ctx({ offline: offline(), mixing: true }))).toEqual({ kind: 'youtube' });
    expect(offlineCopyPlays(yt, ctx({ offline: offline(), mixing: false }))).toBe(true);
    expect(offlineCopyPlays(yt, ctx({ offline: null }))).toBe(false);
  });

  it('puts a linked file before the copy, and the copy before YouTube', async () => {
    const linked = new Blob(['linked']);
    const local = vi.fn(async () => linked);
    expect(await readMedium(resolveMedium(yt, ctx({ local, hasLink: () => true, offline: offline() })))).toMatchObject({ blob: linked });
    const broken = vi.fn(async () => {
      throw new Error('NotReadableError');
    });
    expect(await readMedium(resolveMedium(yt, ctx({ local: broken, hasLink: () => true, offline: offline() })))).toMatchObject({ blob: copy });
    // an unlinked track ignores the local resolver and uses the copy
    expect(await readMedium(resolveMedium(yt, ctx({ local, hasLink: () => false, offline: offline() })))).toMatchObject({ blob: copy });
  });

  it('falls back to YouTube when the copy was evicted', async () => {
    const plan = resolveMedium(yt, ctx({ offline: offline(true, () => Promise.resolve(null)) }));
    expect(await readMedium(plan)).toMatchObject({ kind: 'youtube', fellBack: 'no file' });
  });

  it('leaves local files and clips alone', () => {
    expect(resolveMedium(track(localTrackId(1), 'local'), ctx({ offline: offline() }))).toMatchObject({ kind: 'unavailable' });
  });
});
