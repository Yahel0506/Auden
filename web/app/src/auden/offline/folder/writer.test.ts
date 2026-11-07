import { describe, expect, it, vi } from 'vitest';
import { memoryFiles } from '../files';
import type { OfflineItem } from '../types';
import { memoryDir } from './dirs';
import { DEFAULT_TEMPLATE } from './layout';
import { saveToFolder, type WriterDeps } from './writer';

const bytes = (n: number, v = 7) => new Uint8Array(n).fill(v);
const item = (id: string, over: Partial<OfflineItem> = {}): OfflineItem => ({
  videoId: id, title: `Song ${id}`, artist: 'Artist', album: 'Album', albumId: 'MPREb_1', artistId: null, thumbnailUrl: null, durationSec: 60,
  file: `${id}.webm`, mime: 'audio/webm', bytes: 10, itag: 251, savedAt: 0, cover: null, folderPending: true, ...over,
});

async function setup(items: OfflineItem[], over: Partial<WriterDeps> = {}, caseInsensitive = false) {
  const files = memoryFiles();
  for (const i of items) {
    const sink = await files.begin(i.file);
    await sink.write(bytes(i.bytes));
    await sink.close();
  }
  const root = memoryDir({ caseInsensitive });
  const saved = new Map<string, OfflineItem>();
  // The stored songs: what the writer re-reads inside the lock.
  const stored = new Map(items.map((i) => [i.videoId, i]));
  const store = {
    putItem: vi.fn(async (i: OfflineItem) => void (saved.set(i.videoId, i), stored.set(i.videoId, i))),
    item: vi.fn(async (id: string) => stored.get(id)),
    stored,
  };
  const deps: WriterDeps = { root, template: DEFAULT_TEMPLATE, files, store, now: () => 123, ...over };
  return { root, files, store, saved, deps };
}

const png = new Blob([new Uint8Array(4)], { type: 'image/png' });

describe('saveToFolder', () => {
  it('writes the audio under the template and records where, clearing the pending flag', async () => {
    const a = item('aaaaaaaaaaa');
    const t = await setup([a]);
    const report = await saveToFolder([a], t.deps);
    expect(report).toEqual({ results: [{ videoId: 'aaaaaaaaaaa', status: 'saved', path: 'Album/Artist - Song aaaaaaaaaaa.webm', lrc: false }], stopped: null });
    expect(t.root.files().get('Album/Artist - Song aaaaaaaaaaa.webm')!.byteLength).toBe(10);
    const row = t.saved.get('aaaaaaaaaaa')!;
    expect(row.folder).toEqual({ path: 'Album/Artist - Song aaaaaaaaaaa.webm', savedAt: 123, bytes: 10, lrc: false });
    expect(row.folderPending).toBeUndefined();
  });

  it('never overwrites a file in the way, nor lets two songs share a path (case-insensitively too)', async () => {
    const a = item('aaaaaaaaaaa', { title: 'Same' });
    const b = item('bbbbbbbbbbb', { title: 'same' });
    const c = item('ccccccccccc', { title: 'Same' });
    const t = await setup([a, b, c], {}, true);
    t.root.put('Album/Artist - Same.webm', bytes(3, 9));
    const report = await saveToFolder([a, b, c], t.deps);
    expect(report.results.map((r) => r.path)).toEqual(['Album/Artist - Same (2).webm', 'Album/Artist - same (3).webm', 'Album/Artist - Same (4).webm']);
    expect(t.root.files().get('Album/Artist - Same.webm')).toEqual(bytes(3, 9));
  });

  it('skips a song already saved when its file is still there, and saves again when it was deleted', async () => {
    const a = item('aaaaaaaaaaa', { folderPending: false });
    const t = await setup([a]);
    await saveToFolder([a], t.deps);
    const saved = t.saved.get('aaaaaaaaaaa')!;
    t.store.putItem.mockClear();
    const again = await saveToFolder([saved], t.deps);
    expect(again.results[0]).toMatchObject({ status: 'already' });
    expect(t.store.putItem).not.toHaveBeenCalled();
    await t.root.removeEntry('Album').catch(() => undefined);
    const third = await saveToFolder([saved], t.deps);
    expect(third.results[0]).toMatchObject({ status: 'saved', path: saved.folder!.path });
  });

  it('writes a .lrc beside the audio, never replaces one, and fills a missing one later', async () => {
    const a = item('aaaaaaaaaaa');
    const lyrics = vi.fn(async () => '[00:01.00]hi');
    const t = await setup([a], { lyrics });
    t.root.put('Album/Artist - Song aaaaaaaaaaa.lrc', bytes(2, 1));
    const first = await saveToFolder([a], t.deps);
    // the audio path was free, the .lrc was the listener's: kept as it was
    expect(first.results[0]).toMatchObject({ status: 'saved', lrc: true });
    expect(t.root.files().get('Album/Artist - Song aaaaaaaaaaa.lrc')).toEqual(bytes(2, 1));

    const b = item('bbbbbbbbbbb');
    const t2 = await setup([b], { lyrics: async () => null });
    const saved = await saveToFolder([b], t2.deps);
    expect(saved.results[0]).toMatchObject({ lrc: false });
    const later = await saveToFolder([t2.saved.get('bbbbbbbbbbb')!], { ...t2.deps, lyrics: async () => '[00:01.00]x' });
    expect(later.results[0]).toMatchObject({ status: 'already', lrc: true });
    expect(new TextDecoder().decode(t2.root.files().get('Album/Artist - Song bbbbbbbbbbb.lrc')!)).toBe('[00:01.00]x');
    expect(t2.saved.get('bbbbbbbbbbb')!.folder!.lrc).toBe(true);
  });

  it('a lyrics failure does not fail the song', async () => {
    const a = item('aaaaaaaaaaa');
    const t = await setup([a], { lyrics: async () => Promise.reject(new Error('db')) });
    expect((await saveToFolder([a], t.deps)).results[0]).toMatchObject({ status: 'saved', lrc: false });
  });

  it('writes one cover per album folder, never for Singles or the root, never over an existing one', async () => {
    const a = item('aaaaaaaaaaa', { cover: png });
    const b = item('bbbbbbbbbbb', { cover: png });
    const single = item('sssssssssss', { album: null, albumId: null, cover: png });
    const t = await setup([a, b, single]);
    await saveToFolder([a, b, single], t.deps);
    expect([...t.root.files().keys()].filter((p) => p.includes('cover'))).toEqual(['Album/cover.png']);

    const c = item('ccccccccccc', { cover: png, album: 'Other' });
    const t2 = await setup([c]);
    t2.root.put('Other/cover.png', bytes(1, 5));
    await saveToFolder([c], t2.deps);
    expect(t2.root.files().get('Other/cover.png')).toEqual(bytes(1, 5));

    const flat = item('fffffffffff', { cover: png });
    const t3 = await setup([flat], { template: '{artist} - {title}' });
    await saveToFolder([flat], t3.deps);
    expect([...t3.root.files().keys()].some((p) => p.includes('cover'))).toBe(false);
  });

  it('stops the batch when permission is taken back, leaving the rest pending and untouched', async () => {
    const items = [item('aaaaaaaaaaa'), item('bbbbbbbbbbb'), item('ccccccccccc')];
    const t = await setup(items);
    const read = t.files.read;
    let n = 0;
    t.deps.files = { read: async (name) => (++n === 2 ? (t.root.revoke('NotAllowedError'), read(name)) : read(name)) };
    const report = await saveToFolder(items, t.deps);
    expect(report.stopped).toBe('permission');
    expect(report.results.map((r) => r.videoId)).toEqual(['aaaaaaaaaaa']);
    expect([...t.saved.keys()]).toEqual(['aaaaaaaaaaa']);
  });

  it('stops on a full disk and on a vanished folder, with the reason', async () => {
    const a = item('aaaaaaaaaaa');
    const t = await setup([a, item('bbbbbbbbbbb')]);
    t.root.failWrite('Artist - Song aaaaaaaaaaa.webm', 'QuotaExceededError');
    expect((await saveToFolder([a, item('bbbbbbbbbbb')], t.deps)).stopped).toBe('disk-full');
    expect([...t.root.files().keys()]).toEqual([]);

    const t2 = await setup([a]);
    t2.root.revoke('NotFoundError');
    expect((await saveToFolder([a], t2.deps)).stopped).toBe('folder-missing');
  });

  it('fails one song (locked file) without a partial file and carries on with the rest', async () => {
    const a = item('aaaaaaaaaaa');
    const b = item('bbbbbbbbbbb');
    const t = await setup([a, b]);
    t.root.failWrite('Artist - Song aaaaaaaaaaa.webm', 'NoModificationAllowedError');
    const report = await saveToFolder([a, b], t.deps);
    expect(report.results.map((r) => r.status)).toEqual(['failed', 'saved']);
    expect([...t.root.files().keys()]).toEqual(['Album/Artist - Song bbbbbbbbbbb.webm']);
  });

  it('fails a song whose offline copy is gone, and runs the batch under the lock it is given', async () => {
    const a = item('aaaaaaaaaaa');
    const t = await setup([a]);
    t.files.data.clear();
    let locked = 0;
    const lock: WriterDeps['lock'] = (fn) => (locked++, fn());
    const report = await saveToFolder([a], { ...t.deps, lock });
    expect(report.results[0]).toMatchObject({ status: 'failed', reason: expect.stringContaining('no longer kept') });
    expect(locked).toBe(1);
  });

  it('a createWritable failure leaves no empty file, and the next save uses the original name (I1)', async () => {
    const a = item('aaaaaaaaaaa');
    const t = await setup([a]);
    t.root.failCreate('Artist - Song aaaaaaaaaaa.webm', 'NoModificationAllowedError');
    expect((await saveToFolder([a], t.deps)).results[0]).toMatchObject({ status: 'failed' });
    expect(t.root.files().size).toBe(0);
    expect((await saveToFolder([a], t.deps)).results[0]).toMatchObject({ status: 'saved', path: 'Album/Artist - Song aaaaaaaaaaa.webm' });
  });

  it('a stop while writing the .lrc still records the audio, so the next run does not write it again (I2)', async () => {
    const a = item('aaaaaaaaaaa');
    const t = await setup([a], { lyrics: async () => '[00:01.00]x' });
    t.root.failWrite('Artist - Song aaaaaaaaaaa.lrc', 'NotAllowedError');
    const first = await saveToFolder([a], t.deps);
    expect(first.stopped).toBe('permission');
    expect(first.results[0]).toMatchObject({ status: 'saved', lrc: false });
    expect(t.saved.get('aaaaaaaaaaa')!.folder!.path).toBe('Album/Artist - Song aaaaaaaaaaa.webm');
    const second = await saveToFolder([a], t.deps);
    expect(second.results[0]).toMatchObject({ status: 'already' });
    expect([...t.root.files().keys()].filter((p) => p.endsWith('.webm'))).toEqual(['Album/Artist - Song aaaaaaaaaaa.webm']);
  });

  it('overlapping batches asked with the same stale item write one file (I3), and a removed song is skipped', async () => {
    const a = item('aaaaaaaaaaa');
    const gone = item('ggggggggggg');
    const t = await setup([a, gone]);
    let chain: Promise<unknown> = Promise.resolve();
    const lock: WriterDeps['lock'] = (fn) => {
      const run = chain.then(fn);
      chain = run.catch(() => undefined);
      return run as Promise<never>;
    };
    t.store.stored.delete('ggggggggggg');
    const [one, two] = await Promise.all([saveToFolder([a, gone], { ...t.deps, lock }), saveToFolder([a], { ...t.deps, lock })]);
    expect([...t.root.files().keys()]).toEqual(['Album/Artist - Song aaaaaaaaaaa.webm']);
    expect(one.results.map((r) => r.status)).toEqual(['saved']);
    expect(two.results.map((r) => r.status)).toEqual(['already']);
  });

  it('a file the listener changed afterwards (tagged: other size) is still "already", never written again (I5)', async () => {
    const a = item('aaaaaaaaaaa');
    const t = await setup([a]);
    await saveToFolder([a], t.deps);
    t.root.put('Album/Artist - Song aaaaaaaaaaa.webm', bytes(99, 2));
    const again = await saveToFolder([t.saved.get('aaaaaaaaaaa')!], t.deps);
    expect(again.results[0]).toMatchObject({ status: 'already' });
    expect([...t.root.files().keys()]).toEqual(['Album/Artist - Song aaaaaaaaaaa.webm']);
  });
});
