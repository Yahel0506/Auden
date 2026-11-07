// @vitest-environment happy-dom
import { afterEach, describe, expect, it, vi } from 'vitest';
import { line } from '@/lyrics/model';
import type { LyricsResult } from '@/lyrics/repository';
import { batchPlan, runBatch, type BatchDeps, type BatchSong } from './batch';

const song = (id: string, over: Partial<BatchSong> = {}): BatchSong => ({ videoId: id, title: `T ${id}`, artist: 'A', durationSec: 200, album: null, ...over });
const found = (id: string): LyricsResult => ({ source: 'lrclib', label: 'LRCLIB', lines: [line(1, id)], ttml: '<tt/>', wordSynced: false, synced: true });

afterEach(() => vi.useRealTimers());

describe('batchPlan', () => {
  it('looks each song up once, and leaves out clips and songs with no length', () => {
    const plan = batchPlan([
      song('a'),
      song('a'),
      song('b', { durationSec: null }),
      song('c', { durationSec: 0 }),
      song('auden:clip:1', { source: 'clip' }),
      song('d', { title: '  ' }),
      song('e'),
    ]);
    expect(plan.todo.map((s) => s.videoId)).toEqual(['a', 'e']);
    expect(plan.skipped).toBe(4);
  });
});

describe('runBatch', () => {
  const deps = (over: Partial<BatchDeps> = {}): BatchDeps => ({
    lookup: async (track) => found(track.videoId),
    save: async () => {},
    sequence: ['lrclib'],
    ...over,
  });

  it('saves what it finds and counts the rest', async () => {
    const save = vi.fn(async () => {});
    const result = await runBatch([song('a'), song('b'), song('c')], deps({ lookup: async (t) => (t.videoId === 'b' ? null : found(t.videoId)), save }));
    expect(result).toEqual({ found: 2, missing: 1, skipped: 0 });
    expect(save).toHaveBeenCalledTimes(2);
  });

  it('asks for the sequence it was given, afresh, and gives the lookup a settle time', async () => {
    const lookup = vi.fn(async () => null);
    await runBatch([song('a')], deps({ lookup, sequence: ['kugou', 'lrclib'] }), { settleMs: 1234 });
    expect(lookup).toHaveBeenCalledWith(expect.objectContaining({ videoId: 'a', durationMs: 200_000 }), { sequence: ['kugou', 'lrclib'], fresh: true, settleAfterMs: 1234 });
  });

  it('never runs more than three at a time', async () => {
    let running = 0;
    let peak = 0;
    const lookup = async (t: { videoId: string }) => {
      peak = Math.max(peak, ++running);
      await new Promise((r) => setTimeout(r, 5));
      running--;
      return found(t.videoId);
    };
    await runBatch(Array.from({ length: 10 }, (_, i) => song(`s${i}`)), deps({ lookup }));
    expect(peak).toBe(3);
  });

  it('gives up on a song after its time and moves on', async () => {
    vi.useFakeTimers();
    const lookup = vi.fn((t: { videoId: string }) => (t.videoId === 'slow' ? new Promise<null>(() => {}) : Promise.resolve(found(t.videoId))));
    const run = runBatch([song('slow'), song('fast')], deps({ lookup }), { perSongMs: 1000, parallelism: 1 });
    await vi.advanceTimersByTimeAsync(1500);
    expect(await run).toEqual({ found: 1, missing: 1, skipped: 0 });
  });

  it('counts a lookup that throws as not found and carries on', async () => {
    const lookup = vi.fn(async (t: { videoId: string }) => {
      if (t.videoId === 'a') throw new Error('network');
      return found(t.videoId);
    });
    expect(await runBatch([song('a'), song('b')], deps({ lookup }))).toEqual({ found: 1, missing: 1, skipped: 0 });
  });

  it('counts a save that fails as not found, not as a crash', async () => {
    const result = await runBatch([song('a')], deps({ save: async () => { throw new Error('disk'); } }));
    expect(result).toEqual({ found: 0, missing: 1, skipped: 0 });
  });

  it('stops starting new songs once aborted, and reports progress as it goes', async () => {
    const abort = new AbortController();
    const progress: number[] = [];
    const result = await runBatch(Array.from({ length: 6 }, (_, i) => song(`s${i}`)), deps(), {
      parallelism: 1,
      signal: abort.signal,
      onProgress: (done) => {
        progress.push(done);
        if (done === 2) abort.abort();
      },
    });
    expect(progress).toEqual([1, 2]);
    expect(result.found).toBe(2);
  });

  it('saves only the best version: no extras are fetched behind the three at a time', async () => {
    const lookup = vi.fn(async (track: { videoId: string }) => found(track.videoId));
    const saved: string[] = [];
    await runBatch([song('a'), song('b')], deps({ lookup, save: async (s) => void saved.push(s.videoId) }));
    // One lookup per song, with the given sequence: nothing else goes out.
    expect(lookup).toHaveBeenCalledTimes(2);
    expect(saved).toEqual(['a', 'b']);
  });
});
