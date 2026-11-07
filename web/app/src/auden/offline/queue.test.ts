import 'fake-indexeddb/auto';
import { describe, expect, it, vi } from 'vitest';
import { classify, BACKOFF_MS, MAX_RETRIES, OfflineQueue, type QueueDeps } from './queue';
import { memoryFiles } from './files';
import { changeBus, dbStore, openOfflineDb } from './store';
import type { OfflineSong } from './types';

let counter = 0;
const song = (n: number): OfflineSong => ({ videoId: `vid${String(n).padStart(8, '0')}`, title: `Song ${n}`, artist: 'A', album: 'Alb', albumId: null, artistId: null, thumbnailUrl: null, durationSec: 100 });

function setup(download: QueueDeps['download'], extra: Partial<QueueDeps> = {}) {
  const dbName = `offline-test-${counter++}`;
  const store = dbStore(() => openOfflineDb(dbName));
  const files = memoryFiles();
  const bus = changeBus(`bus-${dbName}`);
  let time = 1_000_000;
  const timers: { at: number; fn: () => void }[] = [];
  const queue = new OfflineQueue({
    store,
    files,
    bus,
    download,
    now: () => time,
    online: () => true,
    spacingMs: 0,
    setTimer: (fn, ms) => {
      const t = { at: time + ms, fn };
      timers.push(t);
      return t;
    },
    clearTimer: (h) => {
      const i = timers.indexOf(h as (typeof timers)[number]);
      if (i >= 0) timers.splice(i, 1);
    },
    ...extra,
  });
  const advance = async (ms: number) => {
    time += ms;
    for (const t of timers.filter((x) => x.at <= time)) {
      timers.splice(timers.indexOf(t), 1);
      t.fn();
    }
    await vi.waitFor(() => expect(true).toBe(true));
  };
  return { queue, store, files, advance, timers, now: () => time };
}

const ok = (song: OfflineSong) => ({ file: `${song.videoId}.webm`, mime: 'audio/webm', bytes: 10, itag: 251 });
const settled = () => new Promise((r) => setTimeout(r, 20));

describe('classify', () => {
  it('splits failures into abort, queue-wide pauses, permanent and retryable', () => {
    expect(classify({ name: 'AbortError' }, true)).toEqual({ kind: 'abort' });
    expect(classify({ name: 'QuotaExceededError' }, true)).toEqual({ kind: 'pause', reason: 'storage-full' });
    expect(classify({ name: 'UnplayableError', ageGated: true }, true)).toEqual({ kind: 'pause', reason: 'verify' });
    expect(classify({ name: 'UnplayableError', ageGated: false, message: 'removed' }, true).kind).toBe('permanent');
    expect(classify({ name: 'AudioStreamError', kind: 'sabr' }, true).kind).toBe('permanent');
    expect(classify({ name: 'AudioStreamError', kind: 'webpo' }, true).kind).toBe('permanent');
    expect(classify({ name: 'AudioStreamError', kind: 'http', status: 410 }, true).kind).toBe('permanent');
    expect(classify({ name: 'AudioStreamError', kind: 'http', status: 500 }, true).kind).toBe('retry');
    expect(classify(new TypeError('Failed to fetch'), false)).toEqual({ kind: 'pause', reason: 'offline' });
    expect(classify(new TypeError('Failed to fetch'), true).kind).toBe('retry');
  });
});

describe('OfflineQueue', () => {
  it('downloads queued songs, keeps them and removes the jobs', async () => {
    const { queue, store } = setup(async (s) => ok(s as OfflineSong));
    queue.setOwner(true);
    await queue.reload();
    expect(await queue.enqueue([song(1), song(2)])).toEqual({ added: 2, skipped: 0 });
    await vi.waitFor(async () => expect((await store.items()).length).toBe(2));
    expect(await store.jobs()).toEqual([]);
    expect(queue.view().rows).toEqual([]);
  });

  it('skips duplicates, bad ids, songs already kept and songs already queued', async () => {
    const { queue, store } = setup(async (s) => ok(s as OfflineSong));
    queue.setOwner(true);
    await queue.enqueue([song(1)]);
    await vi.waitFor(async () => expect((await store.items()).length).toBe(1));
    const bad = { ...song(3), videoId: 'auden:local:1' };
    expect(await queue.enqueue([song(1), song(1), bad])).toEqual({ added: 0, skipped: 3 });
  });

  it('runs at most the concurrency limit at once', async () => {
    let live = 0;
    let peak = 0;
    const gates: (() => void)[] = [];
    const { queue, store } = setup(
      (s) =>
        new Promise((resolve) => {
          live++;
          peak = Math.max(peak, live);
          gates.push(() => {
            live--;
            resolve(ok(s as OfflineSong));
          });
        }),
    );
    queue.setOwner(true);
    await queue.enqueue([song(1), song(2), song(3), song(4)]);
    await vi.waitFor(() => expect(gates.length).toBe(2));
    expect(peak).toBe(2);
    gates.splice(0).forEach((g) => g());
    await vi.waitFor(() => expect(gates.length).toBe(2));
    gates.splice(0).forEach((g) => g());
    await vi.waitFor(async () => expect((await store.items()).length).toBe(4));
    expect(peak).toBe(2);
  });

  it('spaces the starts of transfers', async () => {
    const starts: number[] = [];
    const t = setup(async (s) => (starts.push(t.now()), ok(s as OfflineSong)), { spacingMs: 3_000 });
    t.queue.setOwner(true);
    await t.queue.enqueue([song(1), song(2)]);
    await vi.waitFor(() => expect(starts.length).toBe(1));
    await settled();
    expect(starts.length).toBe(1);
    await t.advance(3_000);
    await vi.waitFor(() => expect(starts.length).toBe(2));
    expect(starts[1]! - starts[0]!).toBeGreaterThanOrEqual(3_000);
  });

  it('retries with back-off, persists the wait, and gives up with the reason', async () => {
    let calls = 0;
    const t = setup(async () => {
      calls++;
      throw new Error('boom');
    });
    t.queue.setOwner(true);
    await t.queue.enqueue([song(1)]);
    for (let i = 0; i < MAX_RETRIES; i++) {
      await vi.waitFor(() => expect(calls).toBe(i + 1));
      await vi.waitFor(async () => expect((await t.store.jobs())[0]?.attempt).toBe(i + 1));
      const job = (await t.store.jobs())[0]!;
      expect(job.notBefore).toBe(t.now() + BACKOFF_MS[i]!);
      expect(t.queue.view().rows[0]!.status).toBe('waiting');
      await t.advance(BACKOFF_MS[i]!);
    }
    await vi.waitFor(async () => expect((await t.store.jobs())[0]?.state).toBe('failed'));
    const failed = (await t.store.jobs())[0]!;
    expect(failed.reason).toContain('boom');
    expect(failed.reason).toContain('gave up');
    expect(calls).toBe(MAX_RETRIES + 1);
  });

  it('fails a permanent error at once, and Retry starts it afresh', async () => {
    let mode: 'sabr' | 'ok' = 'sabr';
    const t = setup(async (s) => {
      if (mode === 'sabr') throw Object.assign(new Error('x'), { name: 'AudioStreamError', kind: 'sabr' });
      return ok(s as OfflineSong);
    });
    t.queue.setOwner(true);
    await t.queue.enqueue([song(1)]);
    await vi.waitFor(async () => expect((await t.store.jobs())[0]?.state).toBe('failed'));
    expect((await t.store.jobs())[0]!.attempt).toBe(0);
    mode = 'ok';
    await t.queue.retry();
    await vi.waitFor(async () => expect((await t.store.items()).length).toBe(1));
  });

  it('pauses the whole queue (keeping the song queued) on a bot check, and resumes', async () => {
    let gated = true;
    const t = setup(async (s) => {
      if (gated) throw Object.assign(new Error('Sign in to confirm'), { name: 'UnplayableError', ageGated: true });
      return ok(s as OfflineSong);
    });
    t.queue.setOwner(true);
    await t.queue.enqueue([song(1), song(2), song(3)]);
    await vi.waitFor(() => expect(t.queue.view().paused?.reason).toBe('verify'));
    await settled();
    const jobs = await t.store.jobs();
    expect(jobs.length).toBe(3);
    expect(jobs.every((j) => j.state === 'queued' && j.attempt === 0)).toBe(true);
    gated = false;
    await t.queue.resume();
    await vi.waitFor(async () => expect((await t.store.items()).length).toBe(3));
  });

  it('pauses on a full disk without spending retries', async () => {
    const t = setup(async () => {
      throw new DOMException('full', 'QuotaExceededError');
    });
    t.queue.setOwner(true);
    await t.queue.enqueue([song(1)]);
    await vi.waitFor(() => expect(t.queue.view().paused?.reason).toBe('storage-full'));
    expect((await t.store.jobs())[0]!.attempt).toBe(0);
  });

  it('pauses when offline and resumes by itself when the connection returns', async () => {
    let online = false;
    const t = setup(
      async (s) => {
        if (!online) throw new TypeError('Failed to fetch');
        return ok(s as OfflineSong);
      },
      { online: () => online },
    );
    t.queue.setOwner(true);
    await t.queue.enqueue([song(1)]);
    await vi.waitFor(() => expect(t.queue.view().paused?.reason).toBe('offline'));
    online = true;
    await t.queue.onOnline();
    await vi.waitFor(async () => expect((await t.store.items()).length).toBe(1));
  });

  it('a listener pause stops the running transfer and leaves it queued; cancel removes it', async () => {
    let aborted = 0;
    const t = setup(
      (_s, ctx) =>
        new Promise((_res, rej) => {
          ctx.signal.addEventListener('abort', () => {
            aborted++;
            rej(new DOMException('Aborted', 'AbortError'));
          });
        }),
    );
    t.queue.setOwner(true);
    await t.queue.enqueue([song(1), song(2)]);
    await vi.waitFor(() => expect(t.queue.view().rows.filter((r) => r.status === 'active').length).toBe(2));
    await t.queue.pause();
    await vi.waitFor(() => expect(aborted).toBe(2));
    await settled();
    expect(t.queue.view().rows.map((r) => r.status)).toEqual(['queued', 'queued']);
    await t.queue.cancel(song(1).videoId);
    await vi.waitFor(() => expect(t.queue.view().rows.length).toBe(1));
  });

  it('comes back after a restart with its jobs, and only the owner transfers', async () => {
    const dl = vi.fn(async (s: OfflineSong) => ok(s));
    const t = setup(dl as QueueDeps['download']);
    await t.queue.enqueue([song(1)]); // not the owner: written, not run
    await settled();
    expect(dl).not.toHaveBeenCalled();
    expect((await t.store.jobs()).length).toBe(1);
    const second = new OfflineQueue({ store: t.store, files: t.files, bus: changeBus('x-' + counter), download: dl as QueueDeps['download'], spacingMs: 0 });
    second.setOwner(true);
    await second.reload();
    await vi.waitFor(async () => expect((await t.store.items()).length).toBe(1));
  });

  it('removes a failed download file when the record cannot be written', async () => {
    const t = setup(async (s) => {
      await t.files.begin('stray.webm').then((k) => k.close());
      return { ...ok(s as OfflineSong), file: 'stray.webm' };
    });
    vi.spyOn(t.store, 'finish').mockRejectedValue(new Error('db'));
    t.queue.setOwner(true);
    await t.queue.enqueue([song(1)]);
    await vi.waitFor(() => expect(t.files.data.has('stray.webm')).toBe(false));
  });

  it('removeItems deletes the file and the record', async () => {
    const t = setup(async (s) => {
      const sink = await t.files.begin(`${(s as OfflineSong).videoId}.webm`);
      await sink.write(new Uint8Array(4));
      await sink.close();
      return ok(s as OfflineSong);
    });
    t.queue.setOwner(true);
    await t.queue.enqueue([song(1)]);
    await vi.waitFor(async () => expect((await t.store.items()).length).toBe(1));
    await t.queue.removeItems([song(1).videoId]);
    expect(await t.store.items()).toEqual([]);
    expect(t.files.data.size).toBe(0);
  });
});

describe('OfflineQueue: cancel races', () => {
  it('a cancel while the cover is being fetched keeps nothing', async () => {
    let release!: (b: null) => void;
    const t = setup(async (s) => ok(s as OfflineSong), { fetchCover: () => new Promise((r) => (release = r)) });
    t.queue.setOwner(true);
    await t.queue.enqueue([{ ...song(1), thumbnailUrl: 'https://x/y.jpg' }]);
    await vi.waitFor(() => expect(release).toBeTypeOf('function'));
    await t.queue.cancel(song(1).videoId);
    release(null);
    await settled();
    expect(await t.store.items()).toEqual([]);
    expect(await t.store.jobs()).toEqual([]);
  });

  it('a step that fails after the cancel does not bring the job back', async () => {
    const t = setup(
      (_s, ctx) =>
        new Promise((_res, rej) => {
          ctx.signal.addEventListener('abort', () => setTimeout(() => rej(new Error('late, not an AbortError')), 5));
        }),
    );
    t.queue.setOwner(true);
    await t.queue.enqueue([song(1)]);
    await vi.waitFor(() => expect(t.queue.view().rows[0]?.status).toBe('active'));
    await t.queue.cancel(song(1).videoId);
    await settled();
    expect(await t.store.jobs()).toEqual([]);
    expect(t.queue.view().rows).toEqual([]);
  });
});

describe('OfflineQueue: the folder wish', () => {
  it('carries toFolder onto the kept item as folderPending, and upserts it on a job already queued', async () => {
    const t = setup(async (s) => ok(s as OfflineSong));
    await t.queue.enqueue([song(1)]);
    expect((await t.store.jobs())[0]?.toFolder).toBeUndefined();
    expect(await t.queue.enqueue([song(1)], { toFolder: true })).toEqual({ added: 1, skipped: 0 });
    expect((await t.store.jobs())[0]?.toFolder).toBe(true);
    expect(await t.queue.enqueue([song(1)], { toFolder: true })).toEqual({ added: 0, skipped: 1 });
    t.queue.setOwner(true);
    await t.queue.reload();
    await vi.waitFor(async () => expect((await t.store.items())[0]?.folderPending).toBe(true));
    await t.queue.enqueue([song(2)]);
    await vi.waitFor(async () => expect((await t.store.items()).length).toBe(2));
    expect((await t.store.item(song(2).videoId))?.folderPending).toBeUndefined();
  });

  it('a folder wish that arrives while the song is downloading is not lost', async () => {
    let release!: () => void;
    const t = setup(
      (s) =>
        new Promise((resolve) => {
          release = () => resolve(ok(s as OfflineSong));
        }),
    );
    t.queue.setOwner(true);
    await t.queue.enqueue([song(1)]);
    await vi.waitFor(() => expect(release).toBeTypeOf('function'));
    await t.queue.enqueue([song(1)], { toFolder: true });
    release();
    await vi.waitFor(async () => expect((await t.store.items())[0]?.folderPending).toBe(true));
  });
});
