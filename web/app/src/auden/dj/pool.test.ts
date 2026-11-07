// @vitest-environment happy-dom
import { describe, expect, it } from 'vitest';
import { newTrack } from '../db/dao';
import type { StoredBucket, TrackRow } from '../db/schema';
import { type PoolSources, buildPool, djHistory, HISTORY_MAX } from './pool';

const NOW = new Date(2026, 10, 15, 12).getTime();
const yt = (n: number) => String.fromCharCode(97 + n).repeat(11);
const row = (id: number, over: Partial<TrackRow> = {}): TrackRow => newTrack({ id, title: `t${id}`, artist: 'Ana', localUri: `auden-file://r/${id}.mp3`, path: `${id}.mp3`, addedAt: NOW - id, genre: 'Rock', year: 1999, ...over });
const bucket = (tracks: StoredBucket['tracks']): StoredBucket => ({ version: 1, month: '2026-11', tracks, artists: [], albums: [], hours: [], days: {}, trackDays: null, artistDays: null, albumDays: null });
const heard = (id: string, plays: number) => ({ id, title: `h-${id}`, artist: 'Bo', album: null, albumId: null, artistId: null, art: null, ms: 1, plays, last: NOW - 1000, days: { '2026-11-14': { ms: 1, plays } } });
const sources = (over: { rows?: TrackRow[]; months?: StoredBucket[]; pls?: Array<{ title: string; rowIds: number[] }>; hasFile?: PoolSources['hasFile'] } = {}): PoolSources => ({
  months: async () => over.months ?? [],
  rows: async () => over.rows ?? [],
  playlists: async () => over.pls ?? [],
  art: new Map(),
  hasFile: 'hasFile' in over ? (over.hasFile ?? null) : () => true,
});

describe('buildPool', () => {
  it('includes files from a chosen folder at zero plays, with their tags, and history-only songs with their plays', async () => {
    const { tracks } = await buildPool(sources({ rows: [row(1), row(2)], months: [bucket([heard(yt(0), 7)])] }), { now: NOW, online: true });
    const local = tracks.find((t) => t.id === 'auden:local:1')!;
    expect(local).toMatchObject({ plays: 0, genre: 'Rock', year: 1999, localUri: 'auden-file://r/1.mp3' });
    expect(tracks.find((t) => t.id === yt(0))).toMatchObject({ plays: 7, plays30: 7 });
  });
  it('joins a file’s own old history to its song, and a linked row is one track', async () => {
    const months = [bucket([heard('auden:local:1', 3), heard(yt(1), 4)])];
    const { tracks } = await buildPool(sources({ rows: [row(1, { videoId: yt(1) })], months }), { now: NOW, online: true });
    expect(tracks.filter((t) => t.id === yt(1))).toHaveLength(1);
    expect(tracks.find((t) => t.id === yt(1))!.plays).toBe(7);
    expect(tracks.some((t) => t.id === 'auden:local:1')).toBe(false);
  });
  it('keeps only what can play now, and never an unguarded id', async () => {
    const months = [bucket([heard(yt(2), 5), heard('content://x/1', 9), heard('auden:clip:z', 9), heard('spotify:q', 9)])];
    const off = await buildPool(sources({ rows: [row(1), row(2)], months, hasFile: (t) => t.videoId === 'auden:local:1' }), { now: NOW, online: false });
    expect(off.tracks.map((t) => t.id)).toEqual(['auden:local:1']);
    const on = await buildPool(sources({ rows: [row(1)], months, hasFile: null }), { now: NOW, online: true });
    expect(on.tracks.map((t) => t.id)).toEqual([yt(2)]); // streamed only, but online; the phone uri, clip and Spotify ids are gone
  });
  it('carries the playlists a song is in, and the playable track for each id', async () => {
    const { tracks, playable } = await buildPool(sources({ rows: [row(1), row(2)], pls: [{ title: 'Road trip', rowIds: [2] }] }), { now: NOW, online: true });
    expect(tracks.find((t) => t.id === 'auden:local:2')!.playlists).toEqual(['Road trip']);
    expect(playable.get('auden:local:2')).toMatchObject({ source: 'local' });
  });
  it('is capped, most-played first', async () => {
    const rows = Array.from({ length: 5 }, (_, i) => row(i + 1));
    const months = [bucket([heard('auden:local:5', 50)])];
    const { tracks } = await buildPool(sources({ rows, months }), { now: NOW, online: true });
    expect(tracks[0]!.id).toBe('auden:local:5');
  });
});

describe('djHistory', () => {
  it('remembers the most recent ids, moves a repeat to the end and caps the list', () => {
    localStorage.clear();
    djHistory.record('a');
    djHistory.record('b');
    djHistory.record('a');
    expect([...djHistory.recent()]).toEqual(['b', 'a']);
    for (let i = 0; i < HISTORY_MAX + 10; i++) djHistory.record(`x${i}`);
    expect(djHistory.recent().size).toBe(HISTORY_MAX);
  });
});
