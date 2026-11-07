import 'fake-indexeddb/auto';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import type { Track } from '@/data/music/types';
import { memoryFiles } from '../files';
import { OfflineQueue } from '../queue';
import { changeBus, dbStore, openOfflineDb } from '../store';
import type { OfflineItem } from '../types';
import { memoryDir, type MemoryDir } from './dirs';
import { DEFAULT_TEMPLATE } from './layout';

let n = 0;
const rt = vi.hoisted(() => ({ current: null as unknown }));
vi.mock('../runtime', () => ({
  getOfflineRuntime: () => rt.current,
  songOf: (t: Track) => ({ videoId: t.videoId, title: t.title, artist: t.artists.map((a) => a.name).join(', '), album: t.album?.name ?? null, albumId: null, artistId: null, thumbnailUrl: null, durationSec: t.durationSec }),
}));
vi.mock('../../db/libraryDao', () => ({ libraryRoots: { all: async () => [] } }));
vi.mock('../../db/persist', () => ({ requestPersistence: async () => true }));
vi.mock('./sidecar', () => ({ lrcTextFor: async () => null }));

const { useFolder, saveSongsToFolder, flushPending, loadFolder, allowAccess, autoSave, setTemplate } = await import('./state');

const track = (id: string): Track => ({ videoId: id, title: `T ${id}`, artists: [{ name: 'A', id: null }], album: { name: 'Alb', id: null }, durationSec: 60, explicit: false, thumbnail: null, index: null, plays: null });
const item = (id: string, over: Partial<OfflineItem> = {}): OfflineItem => ({
  videoId: id, title: `T ${id}`, artist: 'A', album: 'Alb', albumId: null, artistId: null, thumbnailUrl: null, durationSec: 60,
  file: `${id}.webm`, mime: 'audio/webm', bytes: 4, itag: 251, savedAt: 0, cover: null, ...over,
});

async function boot(permission: PermissionState = 'granted') {
  const dbName = `state-test-${n++}`;
  const store = dbStore(() => openOfflineDb(dbName));
  // A fake handle can't be structured-cloned into IndexedDB (a real one can); the record is held in memory here.
  let record: Awaited<ReturnType<typeof store.folder>> = null;
  store.folder = async () => record;
  store.setFolder = async (r) => void (record = r);
  const files = memoryFiles();
  const bus = changeBus(`state-bus-${n}`);
  const queue = new OfflineQueue({ store, files, bus, download: async () => { throw new Error('no network'); }, spacingMs: 0 });
  rt.current = { store, files, bus, queue };
  const dir: MemoryDir = memoryDir();
  dir.permission = permission;
  await store.setFolder({ handle: dir as unknown as FileSystemDirectoryHandle, name: 'chosen', template: DEFAULT_TEMPLATE, chosenAt: 1 });
  useFolder.setState({ loaded: false, record: null, access: 'prompt', overlap: null });
  await loadFolder();
  const keep = async (i: OfflineItem) => {
    const sink = await files.begin(i.file);
    await sink.write(new Uint8Array(4));
    await sink.close();
    await store.finish(i);
  };
  return { store, files, queue, dir, keep };
}

beforeEach(() => void (rt.current = null));

describe('folder state', () => {
  it('loads the stored folder with its permission, without asking', async () => {
    const t = await boot('prompt');
    expect(useFolder.getState()).toMatchObject({ loaded: true, access: 'prompt' });
    expect(t.dir.requests).toBe(0);
  });

  it('Save to Folder asks for permission first, writes kept songs, and queues the rest with the folder wish', async () => {
    const t = await boot('prompt');
    await t.keep(item('aaaaaaaaaaa'));
    const out = await saveSongsToFolder([track('aaaaaaaaaaa'), track('bbbbbbbbbbb')]);
    expect(t.dir.requests).toBe(1);
    expect(out).toMatchObject({ kind: 'ok', saved: 1, queued: 1 });
    expect([...t.dir.files().keys()]).toEqual(['Alb/A - T aaaaaaaaaaa.webm']);
    expect((await t.store.jobs()).find((j) => j.videoId === 'bbbbbbbbbbb')?.toFolder).toBe(true);
    expect((await t.store.item('aaaaaaaaaaa'))?.folder?.path).toBe('Alb/A - T aaaaaaaaaaa.webm');
  });

  it('a song already on its way only gains the folder wish', async () => {
    const t = await boot();
    await t.queue.enqueue([{ videoId: 'bbbbbbbbbbb', title: 'x', artist: 'A', album: null, albumId: null, artistId: null, thumbnailUrl: null, durationSec: 1 }]);
    const out = await saveSongsToFolder([track('bbbbbbbbbbb')]);
    expect(out).toMatchObject({ kind: 'ok', queued: 1 });
    expect((await t.store.jobs())[0]?.toFolder).toBe(true);
  });

  it('with the permission refused, nothing is written or queued', async () => {
    const t = await boot('prompt');
    t.dir.willGrant = 'denied';
    await t.keep(item('aaaaaaaaaaa'));
    expect(await saveSongsToFolder([track('aaaaaaaaaaa'), track('bbbbbbbbbbb')])).toEqual({ kind: 'no-access' });
    expect(t.dir.files().size).toBe(0);
    expect(await t.store.jobs()).toEqual([]);
  });

  it('with no folder chosen, says so', async () => {
    await boot();
    useFolder.setState({ record: null });
    expect(await saveSongsToFolder([track('aaaaaaaaaaa')])).toEqual({ kind: 'no-folder' });
  });

  it('a finished download waits when permission is missing, and is written once access is allowed', async () => {
    const t = await boot('prompt');
    await t.keep(item('aaaaaaaaaaa', { folderPending: true }));
    await autoSave((await t.store.item('aaaaaaaaaaa'))!);
    expect(t.dir.files().size).toBe(0);
    expect((await t.store.item('aaaaaaaaaaa'))?.folderPending).toBe(true);
    expect(await allowAccess()).toBe(true);
    await vi.waitFor(async () => expect((await t.store.item('aaaaaaaaaaa'))?.folderPending).toBeUndefined());
    expect(t.dir.files().size).toBe(1);
  });

  it('flushPending writes what waited, only when granted', async () => {
    const t = await boot('granted');
    await t.keep(item('aaaaaaaaaaa', { folderPending: true }));
    await flushPending();
    expect(t.dir.files().size).toBe(1);
  });

  it('a template change applies to later saves, and a bad template is refused', async () => {
    const t = await boot();
    expect(await setTemplate('../{title}')).toBe(false);
    expect(await setTemplate('{artist} - {title}')).toBe(true);
    await t.keep(item('aaaaaaaaaaa'));
    await saveSongsToFolder([track('aaaaaaaaaaa')]);
    expect([...t.dir.files().keys()]).toEqual(['A - T aaaaaaaaaaa.webm']);
  });

  it('follows a folder forgotten or changed elsewhere, even with this tab\'s cache stale (I4)', async () => {
    const t = await boot();
    await t.keep(item('aaaaaaaaaaa'));
    await t.store.setFolder(null);
    expect(await saveSongsToFolder([track('aaaaaaaaaaa')])).toMatchObject({ kind: 'ok', saved: 0 });
    expect(t.dir.files().size).toBe(0);
    const { saveNow } = await import('./state');
    expect(await saveNow([(await t.store.item('aaaaaaaaaaa'))!])).toMatchObject({ stopped: 'no-folder' });
  });
});
