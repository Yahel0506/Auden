// @vitest-environment happy-dom
import 'fake-indexeddb/auto';
import { IDBFactory } from 'fake-indexeddb';
import { afterEach, beforeEach, describe, expect, it } from 'vitest';
import type { Track } from '@/data/music/types';
import { setLocalResolver, getLinkProbe, resolveMedium } from '@/playback/source';
import { newTrack } from '../db/dao';
import { localUriOf } from '../db/libraryDao';
import { closeAudenDb } from '../db/schema';
import { hasLink, isOnDevice, linksOf, rebuildLinkIndex } from './linkIndex';
import { resolveFile, installLocalLibrary } from './localResolver';
import { sessionFiles, useRoots } from './roots';

beforeEach(() => {
  globalThis.indexedDB = new IDBFactory();
  sessionFiles.clear();
  useRoots.setState({ roots: [], access: {}, loaded: true });
});
afterEach(async () => {
  setLocalResolver(null);
  await closeAudenDb();
});

const yt = (videoId: string, title = 'Song', artist = 'Artist', durationSec = 200): Track => ({
  videoId, title, artists: [{ name: artist, id: null }], album: null, durationSec, explicit: false, thumbnail: null, index: null, plays: null,
});
const row = (id: number, over: Parameters<typeof newTrack>[0]) => newTrack({ id, localUri: localUriOf('r', `f${id}.mp3`), ...over });

describe('link index', () => {
  it('links by YouTube id and by title/artist/duration, not by a different song', () => {
    rebuildLinkIndex([
      row(1, { title: 'Song', artist: 'Artist', videoId: 'idAAAAAAAAA' }),
      row(2, { title: 'Other (Live)', artist: 'Artist', durationMs: 201_000 }),
      row(3, { title: 'Song', artist: 'Someone Else', durationMs: 200_000 }),
    ]);
    expect(linksOf(yt('idAAAAAAAAA', 'Different title')).map((l) => l.rowId)).toEqual([1]);
    expect(linksOf(yt('zzzzzzzzzzz', 'Other', 'Artist', 200)).map((l) => l.rowId)).toEqual([2]);
    expect(linksOf(yt('zzzzzzzzzzy', 'Song', 'Nobody', 200))).toEqual([]);
    expect(isOnDevice(yt('zzzzzzzzzzz', 'Other', 'Artist', 200))).toBe(true);
  });
  it('finds a local track by its row id', () => {
    rebuildLinkIndex([row(5, { title: 'L', artist: 'A' })]);
    expect(linksOf({ ...yt('auden:local:5'), source: 'local' }).map((l) => l.rowId)).toEqual([5]);
    expect(linksOf({ ...yt('auden:local:6'), source: 'local' })).toEqual([]);
  });
  it('is on the device only while its folder is usable (hasLink), but the mark stays', () => {
    rebuildLinkIndex([row(1, { title: 'Song', artist: 'Artist', videoId: 'idAAAAAAAAA' })]);
    const t = yt('idAAAAAAAAA');
    useRoots.setState({ access: { r: 'prompt' } });
    expect(hasLink(t)).toBe(false);
    expect(isOnDevice(t)).toBe(true);
    useRoots.setState({ access: { r: 'granted' } });
    expect(hasLink(t)).toBe(true);
  });
});

describe('resolver contract', () => {
  it('answers "no link" without reading anything, and the player stays on the sync YouTube path', () => {
    rebuildLinkIndex([]);
    const stop = installLocalLibrary();
    const probe = getLinkProbe()!;
    expect(probe(yt('nolinkAAAAA'))).toBe(false);
    const plan = resolveMedium(yt('nolinkAAAAA'), { inParty: false, clip: () => null, local: resolveFile, hasLink: probe });
    expect(plan).toEqual({ kind: 'youtube' });
    stop();
  });
  it('returns the picked File of a connected "files" root, and null when its size no longer matches or the root is offline', async () => {
    const file = new File(['abcd'], 'f1.mp3', { type: 'audio/mpeg' });
    rebuildLinkIndex([row(1, { title: 'Song', artist: 'Artist', videoId: 'idAAAAAAAAA', sizeBytes: 4 })]);
    expect(await resolveFile(yt('idAAAAAAAAA'))).toBeNull(); // offline: no session files
    sessionFiles.set('r', new Map([['f1.mp3', file]]));
    useRoots.setState({ access: { r: 'files' } });
    expect(await resolveFile(yt('idAAAAAAAAA'))).toBe(file);
    rebuildLinkIndex([row(1, { title: 'Song', artist: 'Artist', videoId: 'idAAAAAAAAA', sizeBytes: 99 })]);
    expect(await resolveFile(yt('idAAAAAAAAA'))).toBeNull();
  });
  it('never asks for permission: a root that needs a tap resolves to null', async () => {
    rebuildLinkIndex([row(1, { title: 'Song', artist: 'Artist', videoId: 'idAAAAAAAAA' })]);
    useRoots.setState({ access: { r: 'prompt' } });
    expect(await resolveFile(yt('idAAAAAAAAA'))).toBeNull();
  });
});
