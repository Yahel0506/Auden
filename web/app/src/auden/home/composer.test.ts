import { describe, expect, it } from 'vitest';
import { compose, DEFAULT_WEIGHT, type HomeEntry, type HomeSettings } from './composer';
import { LOCAL_SHELF_KINDS, LocalShelfKind, type LocalShelf } from './shelves';

interface RemoteShelf {
  title: string;
  items: string[];
}

const remoteShelf = (n: number): RemoteShelf => ({ title: `R${n}`, items: [`v${n}`] });
const localShelf = (kind: LocalShelfKind): LocalShelf => ({
  kind,
  title: kind,
  subtitle: '',
  items: [{ id: 'v1', title: 't1', subtitle: '', art: null, kind: 'track' }],
});

const hasItems = (r: RemoteShelf) => r.items.length > 0;
const titles = <R extends { title: string }>(entries: Array<HomeEntry<R>>) => entries.map((e) => e.shelf.title);
const settingsOf = (over: Partial<HomeSettings> = {}): HomeSettings => ({
  enabled: new Set<LocalShelfKind>(LOCAL_SHELF_KINDS),
  localWeight: DEFAULT_WEIGHT,
  ...over,
});

const range = (from: number, to: number): number[] => Array.from({ length: to - from + 1 }, (_, i) => from + i);

const remote = range(1, 4).map(remoteShelf);
const local = [LocalShelfKind.LOOP, LocalShelfKind.PLAYLISTS, LocalShelfKind.RECENTLY_ADDED].map(localShelf);

describe('home composition', () => {
  it('weight zero is the feed alone', () => {
    const out = compose(remote, local, settingsOf({ localWeight: 0 }), true, hasItems);
    expect(titles(out)).toEqual(titles(remote.map((shelf) => ({ side: 'remote' as const, shelf }))));
    expect(out.every((e) => e.side === 'remote')).toBe(true);
  });

  it('weight one hundred puts every local shelf first', () => {
    const out = compose(remote, local, settingsOf({ localWeight: 100 }), true, hasItems);
    expect(titles(out)).toEqual(['LOOP', 'PLAYLISTS', 'RECENTLY_ADDED', 'R1', 'R2', 'R3', 'R4']);
  });

  it('the feed leads and the sources alternate in between', () => {
    const out = compose(remote, local, settingsOf({ localWeight: 50 }), true, hasItems);
    expect(titles(out)[0]).toBe('R1');
    expect(out.length).toBe(7);
    // No two local shelves in a row at an even weight.
    const sides = out.map((e) => e.side);
    expect(sides.some((side, i) => side === 'local' && sides[i + 1] === 'local')).toBe(false);
  });

  it('offline is only the device', () => {
    const out = compose(remote, local, settingsOf(), false, hasItems);
    expect(titles(out)).toEqual(['LOOP', 'PLAYLISTS', 'RECENTLY_ADDED']);
    expect(out.every((e) => e.side === 'local')).toBe(true);
  });

  it('a disabled kind and an empty shelf are left out', () => {
    const settings = settingsOf({ enabled: new Set([LocalShelfKind.LOOP]), localWeight: 100 });
    const emptyShelf: LocalShelf = { kind: LocalShelfKind.LOOP, title: 'EMPTY', subtitle: '', items: [] };
    const out = compose(remote, [...local, emptyShelf], settings, true, hasItems);
    expect(titles(out)).toEqual(['LOOP', 'R1', 'R2', 'R3', 'R4']);
  });

  it('nothing local leaves the feed untouched, and nothing remote leaves the device', () => {
    const feed = compose(remote, [], settingsOf(), true, hasItems);
    expect(titles(feed)).toEqual(titles(remote.map((shelf) => ({ side: 'remote' as const, shelf }))));

    const device = compose<RemoteShelf>([], local, settingsOf(), true, hasItems);
    expect(titles(device)).toEqual(['LOOP', 'PLAYLISTS', 'RECENTLY_ADDED']);
    expect(device.every((e) => e.side === 'local')).toBe(true);
  });

  it('a high weight leads with the listener\'s own', () => {
    const out = compose(remote, local, settingsOf({ localWeight: 80 }), true, hasItems);
    expect(titles(out)[0]).toBe('LOOP');
  });
});
