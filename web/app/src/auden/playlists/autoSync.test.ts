// @vitest-environment happy-dom
import 'fake-indexeddb/auto';
import { IDBFactory } from 'fake-indexeddb';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import type { Track } from '@/data/music/types';
import { useJobs } from '../jobs/jobs';
import { closeAudenDb } from '../db/schema';
import { type Stamps, type SyncEnv, FAILURE_BACKOFF_MS, MIN_INTERVAL_MS, announce, isDue, offlinePassAllowed, offlineSummary, runDue, runOnce, summaryText } from './autoSync';
import { saveYouTubeList, spotifySourceId, saveSpotifyPlaylist } from './cloudSaver';
import { bySource, entriesOf } from './store';

beforeEach(() => {
  globalThis.indexedDB = new IDBFactory();
  useJobs.setState({ jobs: [] });
});
afterEach(async () => {
  vi.unstubAllGlobals();
  await closeAudenDb();
});

const song = (n: number): Track => ({
  videoId: String.fromCharCode(97 + n).repeat(11), title: `S${n}`, artists: [{ name: 'A', id: null }], album: null, durationSec: 200, explicit: false, thumbnail: null, index: null, plays: null,
});
const memoryStamps = (): Stamps & { map: Map<string, { at: number; ok: boolean }> } => {
  const map = new Map<string, { at: number; ok: boolean }>();
  return { map, get: (k) => map.get(k), set: (k, v) => void map.set(k, v) };
};

function env(over: Partial<SyncEnv> & { remote?: () => Track[] } = {}): SyncEnv {
  let clock = 1_000_000_000;
  const remote = over.remote ?? (() => [song(0), song(1)]);
  return {
    now: () => clock,
    settings: () => ({ enabled: true, alsoOnDataSaver: false }),
    dataSaver: () => false,
    spotifySignedIn: () => true,
    copies: async () => {
      const yt = await bySource('PL1');
      const sp = await bySource(spotifySourceId('P1'));
      return [yt, sp].flatMap((p) => (p?.sourceId ? [{ sourceId: p.sourceId, title: p.title, playlistId: p.id }] : []));
    },
    deps: {
      youtube: async () => ({ title: 'Mix', thumbnail: null, tracks: remote() }),
      spotify: { tracks: async () => ({ items: [{ id: 's1' }], complete: true }), match: async () => song(5) },
    },
    stamps: memoryStamps(),
    pause: async () => {
      clock += 1;
    },
    ...over,
  };
}

const seedCopies = async () => {
  const d = env().deps as never;
  await saveYouTubeList(d, 'VLPL1');
  await saveSpotifyPlaylist(d, 'P1', { title: 'Sp' });
};

describe('isDue', () => {
  it('never tried is due; ok waits six hours; failed waits an hour', () => {
    expect(isDue(undefined, 5)).toBe(true);
    expect(isDue({ at: 0, ok: true }, MIN_INTERVAL_MS - 1)).toBe(false);
    expect(isDue({ at: 0, ok: true }, MIN_INTERVAL_MS)).toBe(true);
    expect(isDue({ at: 0, ok: false }, FAILURE_BACKOFF_MS - 1)).toBe(false);
    expect(isDue({ at: 0, ok: false }, FAILURE_BACKOFF_MS)).toBe(true);
  });
});

describe('runDue', () => {
  it('adds new songs to due copies, stamps them, and does nothing the second time inside six hours', async () => {
    await seedCopies();
    const e = env({ remote: () => [song(0), song(1), song(2)] });
    const first = await runDue(e);
    expect(first).toMatchObject({ tried: 2, added: 1, skipped: null });
    expect((await entriesOf((await bySource('PL1'))!.id)).length).toBe(3);
    expect((await runDue(e)).tried).toBe(0);
  });
  it('is off, or held back by data saver unless allowed', async () => {
    await seedCopies();
    expect((await runDue(env({ settings: () => ({ enabled: false, alsoOnDataSaver: true }) }))).skipped).toBe('off');
    expect((await runDue(env({ dataSaver: () => true }))).skipped).toBe('data-saver');
    expect((await runDue(env({ dataSaver: () => true, settings: () => ({ enabled: true, alsoOnDataSaver: true }) }))).skipped).toBeNull();
  });
  it('skips a Spotify copy without stamping it when Spotify is not signed in', async () => {
    await seedCopies();
    const e = env({ spotifySignedIn: () => false });
    const r = await runDue(e);
    expect(r.tried).toBe(1);
    expect((e.stamps as ReturnType<typeof memoryStamps>).map.has(spotifySourceId('P1'))).toBe(false);
  });
  it('a failure is stamped as failed (an hour of backoff) and changes nothing', async () => {
    await seedCopies();
    const e = env({ remote: () => [] });
    const r = await runDue(e);
    expect(r.added).toBe(0);
    expect((e.stamps.get('PL1'))).toMatchObject({ ok: false });
    expect((await entriesOf((await bySource('PL1'))!.id)).length).toBe(2);
  });
  it('stops when cancelled (the tab was hidden)', async () => {
    await seedCopies();
    const r = await runDue(env({ cancelled: () => true }));
    expect(r.tried).toBe(0);
  });
  it('never calls anything that writes to a service: the deps are read-only functions', async () => {
    await seedCopies();
    const e = env();
    expect(Object.keys(e.deps).sort()).toEqual(['spotify', 'youtube']);
    expect(Object.keys((e.deps as { spotify: object }).spotify).sort()).toEqual(['match', 'tracks']);
  });
});

describe('announce / runOnce', () => {
  it('speaks on the job banner only when songs arrived', () => {
    announce({ skipped: null, tried: 2, added: 0, removed: 3 });
    expect(useJobs.getState().jobs).toHaveLength(0);
    announce({ skipped: null, tried: 2, added: 2, removed: 0 });
    expect(useJobs.getState().jobs[0]).toMatchObject({ detail: summaryText(2), finished: true });
  });
  it('two runs at once: the second finds the lock held and does nothing', async () => {
    // happy-dom has no Web Locks: a minimal one with `ifAvailable` semantics.
    const held = new Set<string>();
    vi.stubGlobal('navigator', {
      locks: {
        request: async (name: string, a: unknown, b?: (lock: object | null) => Promise<unknown>) => {
          const fn = (b ?? a) as (lock: object | null) => Promise<unknown>;
          const ifAvailable = b !== undefined;
          if (held.has(name)) return ifAvailable ? fn(null) : fn({});
          held.add(name);
          try {
            return await fn({});
          } finally {
            held.delete(name);
          }
        },
      },
    });
    await seedCopies();
    expect(await bySource('PL1')).toBeDefined();
    const slow = env({ pause: () => new Promise((r) => setTimeout(r, 30)), remote: () => [song(0), song(1), song(2)] });
    const [a, b] = await Promise.all([runOnce(slow), runOnce(slow)]);
    expect([a, b].filter((x) => x === null)).toHaveLength(1);
  });
});


describe('the offline pass (Keep playlists updated)', () => {
  it('needs copies on, the switch on, and a link that is not metered', () => {
    expect(offlinePassAllowed({ enabled: true, keepUpdated: true }, false)).toBe(true);
    expect(offlinePassAllowed({ enabled: false, keepUpdated: true }, false)).toBe(false);
    expect(offlinePassAllowed({ enabled: true, keepUpdated: false }, false)).toBe(false);
    expect(offlinePassAllowed({ enabled: true, keepUpdated: true }, true)).toBe(false);
  });

  it('runs after the cloud copies even when their own switch is off, under the same lock, and speaks only when it did something', async () => {
    const calls: string[] = [];
    const e = env({
      settings: () => ({ enabled: false, alsoOnDataSaver: false }),
      offline: async () => {
        calls.push('offline');
        return { queued: 3, asking: 1 };
      },
    });
    await runOnce(e);
    expect(calls).toEqual(['offline']);
    expect(useJobs.getState().jobs.at(-1)).toMatchObject({ detail: '3 new songs queued for offline; 1 playlist is waiting for your OK on the Offline page' });
  });

  it('a failure in it does not take the cloud pass’s report with it', async () => {
    const e = env({ offline: async () => Promise.reject(new Error('boom')) });
    await expect(runOnce(e)).resolves.toMatchObject({ skipped: null });
  });

  it('has no words when nothing happened', () => {
    expect(offlineSummary({ queued: 0, asking: 0 })).toBeNull();
    expect(offlineSummary({ queued: 1, asking: 0 })).toBe('1 new song queued for offline');
  });
});
