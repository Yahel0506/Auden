import { describe, expect, it } from 'vitest';
import { newTrack } from '../db/dao';
import type { StoredBucket, TrackEntry, TrackRow } from '../db/schema';
import { type HomeSources, loadHomeInputs, mergeHistory, playableNow } from './data';
import { buildLocalShelves, LocalShelfKind, textFor } from './shelves';

const NOW = new Date(2026, 10, 15, 12).getTime();
const DAY = 86_400_000;
const dayKey = (ms: number) => {
  const d = new Date(ms);
  return `${d.getFullYear()}-${String(d.getMonth() + 1).padStart(2, '0')}-${String(d.getDate()).padStart(2, '0')}`;
};

const entry = (id: string, over: Partial<TrackEntry> = {}): TrackEntry => ({
  id, title: `t-${id}`, artist: 'Ana', album: null, albumId: null, artistId: null, art: null, ms: 1000, plays: 3, last: NOW - DAY,
  days: { [dayKey(NOW - 2 * DAY)]: { ms: 1000, plays: 3 } }, ...over,
});
const bucket = (month: string, tracks: TrackEntry[]): StoredBucket => ({
  version: 1, month, tracks, artists: [], albums: [], hours: [], days: {}, trackDays: null, artistDays: null, albumDays: null,
});
const row = (id: number, over: Partial<TrackRow> = {}): TrackRow =>
  newTrack({ id, title: `row${id}`, artist: 'Ana', localUri: `auden-file://r/${id}.mp3`, path: `${id}.mp3`, addedAt: NOW - id * DAY, ...over });

const ytId = (n: number) => String.fromCharCode(97 + n).repeat(11);
const sources = (over: { months?: StoredBucket[]; rows?: TrackRow[]; hasFile?: HomeSources['hasFile'] } = {}): HomeSources => ({
  months: async () => over.months ?? [],
  rows: async () => over.rows ?? [],
  playlists: async () => [],
  art: new Map(),
  hasFile: 'hasFile' in over ? (over.hasFile ?? null) : () => true,
});

describe('the id guard on stats', () => {
  it('keeps real YouTube ids and existing local rows; drops phone uris, clips, Spotify ids, blanks and vanished rows', async () => {
    const months = [bucket('2026-11', [entry(ytId(0)), entry('content://media/1'), entry('auden:clip:x'), entry('spotify:abc'), entry(''), entry('auden:local:99'), entry('auden:local:1'), entry('auden-file://r/2.mp3')])];
    const out = await loadHomeInputs(sources({ months, rows: [row(1)] }), { now: NOW, online: true });
    expect(out.played.map((p) => p.id).sort()).toEqual([ytId(0), 'auden:local:1'].sort());
  });
  it('an `auden:local` id whose row now has a YouTube id becomes that YouTube song, once with its library row', async () => {
    const out = await loadHomeInputs(
      sources({ months: [bucket('2026-11', [entry('auden:local:1', { plays: 5 }), entry(ytId(1))])], rows: [row(1, { videoId: ytId(1) })] }),
      { now: NOW, online: true },
    );
    expect(out.played.map((p) => p.id)).toEqual([ytId(1), ytId(1)]); // both histories are the same song...
    expect(out.library.map((l) => l.id)).toEqual([ytId(1)]);
    expect(out.tracks.size).toBe(1); // ...and one playable track
  });
  it('keeps a linked file once in the artist mix (history id == library id)', async () => {
    const played = [entry(ytId(2)), entry(ytId(3))].map((e) => ({ ...e }));
    const rows = [row(1, { videoId: ytId(2) }), row(2), row(3), row(4)];
    const out = await loadHomeInputs(sources({ months: [bucket('2026-11', played)], rows }), { now: NOW, online: true });
    const shelves = buildLocalShelves(out.played, out.library, out.playlists, NOW, textFor('en'));
    const mix = shelves.find((s) => s.kind === LocalShelfKind.ARTIST_MIX)!;
    const ids = mix.items.map((i) => i.id);
    expect(new Set(ids).size).toBe(ids.length);
    expect(ids.filter((id) => id === ytId(2))).toHaveLength(1);
  });
});

describe('history', () => {
  it('merges months: plays add, last is latest, days union, newest text', () => {
    const a = entry(ytId(0), { plays: 2, last: 10, title: 'old', days: { '2026-10-01': { ms: 1, plays: 2 } } });
    const b = entry(ytId(0), { plays: 3, last: 20, title: 'new', days: { '2026-11-01': { ms: 1, plays: 3 } } });
    const [m] = mergeHistory([bucket('2026-11', [b]), bucket('2026-10', [a])]);
    expect(m).toMatchObject({ plays: 5, last: 20, title: 'new' });
    expect(Object.keys(m!.days).sort()).toEqual(['2026-10-01', '2026-11-01']);
  });
  it('plays30 counts only the last 30 days (inclusive), and tolerates an entry with no days', async () => {
    const e = entry(ytId(0), { days: { [dayKey(NOW - 30 * DAY)]: { ms: 1, plays: 2 }, [dayKey(NOW - 31 * DAY)]: { ms: 1, plays: 9 } } });
    const legacy = { ...entry(ytId(1)), days: undefined as never };
    const out = await loadHomeInputs(sources({ months: [bucket('2026-11', [e, legacy])] }), { now: NOW, online: true });
    expect(out.played.find((p) => p.id === ytId(0))!.plays30).toBe(2);
    expect(out.played.find((p) => p.id === ytId(1))!.plays30).toBe(0);
  });
});

describe('offline', () => {
  it('drops streamed-only songs before the shelves are built, so the four-song rule still holds', async () => {
    const months = [bucket('2026-11', [0, 1, 2, 3, 4].map((n) => entry(ytId(n), { plays: 9 })))];
    const rows = [row(1, { videoId: ytId(0) }), row(2, { videoId: ytId(1) })];
    const hasFile = (t: { videoId: string }) => t.videoId === ytId(0) || t.videoId === ytId(1);
    const off = await loadHomeInputs(sources({ months, rows, hasFile }), { now: NOW, online: false });
    expect(off.played.map((p) => p.id).sort()).toEqual([ytId(0), ytId(1)]);
    expect(buildLocalShelves(off.played, off.library, off.playlists, NOW, textFor('en')).some((s) => s.kind === LocalShelfKind.LOOP)).toBe(false);
    const on = await loadHomeInputs(sources({ months, rows, hasFile }), { now: NOW, online: true });
    expect(on.played).toHaveLength(5);
  });
  it('a null link probe drops everything that is only streamed, and a local file needs its folder connected', () => {
    const yt = { videoId: ytId(0) } as never;
    const local = { videoId: 'auden:local:1', source: 'local' } as never;
    expect(playableNow(yt, false, null)).toBe(false);
    expect(playableNow(yt, true, null)).toBe(true);
    expect(playableNow(local, true, () => false)).toBe(false);
    expect(playableNow(local, false, () => true)).toBe(true);
  });
});

describe('no extension (streamed songs cannot play): same as offline for the shelves', () => {
  it('keeps only songs with a readable file', async () => {
    const months = [bucket('2026-11', [entry(ytId(0)), entry(ytId(1)), entry('auden:local:5')])];
    const out = await loadHomeInputs(sources({ months, rows: [row(5)], hasFile: (t) => t.videoId === 'auden:local:5' }), { now: NOW, online: false });
    expect(out.played.map((p) => p.id)).toEqual(['auden:local:5']);
  });
});
