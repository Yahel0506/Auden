import { describe, expect, it } from 'vitest';
import { auditOffline, formatBytes, repairOffline, usageBy } from './audit';
import { memoryFiles } from './files';
import type { OfflineItem } from './types';

const item = (id: string, over: Partial<OfflineItem> = {}): OfflineItem => ({
  videoId: id, title: id, artist: 'A & B', album: 'Alb', albumId: 'MPREb_1', artistId: null, thumbnailUrl: null, durationSec: 60,
  file: `${id}.webm`, mime: 'audio/webm', bytes: 10, itag: 251, savedAt: 0, cover: null, ...over,
});
const store = (items: OfflineItem[]) => ({ items: async () => items, deleted: [] as string[], deleteItem: async function (this: { deleted: string[] }, id: string) { this.deleted.push(id); } });

async function folder(files: Record<string, number>) {
  const f = memoryFiles();
  for (const [name, n] of Object.entries(files)) {
    const s = await f.begin(name);
    await s.write(new Uint8Array(n));
    await s.close();
  }
  return f;
}

describe('auditOffline', () => {
  it('reports orphans, missing and damaged copies, and counts what is kept', async () => {
    const files = await folder({ 'aaaaaaaaaaa.webm': 10, 'bbbbbbbbbbb.webm': 7, 'stray.webm': 5, 'cccccccccc1.webm': 0 });
    const s = store([item('aaaaaaaaaaa'), item('bbbbbbbbbbb'), item('ddddddddddd')]);
    const report = await auditOffline(s, files, new Set(['cccccccccc1']));
    expect(report.orphans.map((o) => o.name)).toEqual(['stray.webm']);
    expect(report.broken.map((b) => [b.item.videoId, b.why])).toEqual([['bbbbbbbbbbb', 'size'], ['ddddddddddd', 'missing']]);
    expect(report.keptBytes).toBe(10);
    expect(report.orphanBytes).toBe(5);
  });

  it('repairs by deleting orphans and dangling records, and a damaged file too', async () => {
    const files = await folder({ 'aaaaaaaaaaa.webm': 10, 'bbbbbbbbbbb.webm': 7, 'stray.webm': 5 });
    const s = store([item('aaaaaaaaaaa'), item('bbbbbbbbbbb'), item('ddddddddddd')]);
    const report = await auditOffline(s, files);
    expect(await repairOffline(report, s, files)).toEqual({ files: 1, records: 2 });
    expect([...files.data.keys()]).toEqual(['aaaaaaaaaaa.webm']);
    expect(s.deleted).toEqual(['bbbbbbbbbbb', 'ddddddddddd']);
  });
});

describe('usageBy', () => {
  it('groups by album and by first artist, biggest first, with singles apart', () => {
    const items = [item('a', { bytes: 5 }), item('b', { bytes: 20 }), item('c', { album: null, albumId: null, bytes: 7, artist: 'Z' })];
    const byAlbum = usageBy(items, 'album');
    expect(byAlbum.map((g) => [g.name, g.songs, g.bytes])).toEqual([['Alb', 2, 25], ['Singles', 1, 7]]);
    expect(usageBy(items, 'artist').map((g) => g.name)).toEqual(['A', 'Z']);
  });
  it('formats bytes', () => {
    expect(formatBytes(500)).toBe('500 B');
    expect(formatBytes(1536)).toBe('1.5 KB');
    expect(formatBytes(5 * 1024 * 1024)).toBe('5.0 MB');
  });
});
