// @vitest-environment happy-dom
import { act, createElement } from 'react';
import { createRoot, type Root } from 'react-dom/client';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { clipTrackId } from '@/playback/source';

(globalThis as { IS_REACT_ACT_ENVIRONMENT?: boolean }).IS_REACT_ACT_ENVIRONMENT = true;
vi.mock('@/playback/deck', () => ({ MediaDeck: class {} }));
vi.mock('@/playback/fileEngine', () => ({ FileEngine: class {} }));

const { usePlayer } = await import('@/state/player');
const { useDj } = await import('../session');
const { MESH_CYCLE_MS, paletteAt, paletteCells } = await import('./djScreen');
const { useDjMeshColors, useDjScreen } = await import('./useDjScreen');
const { useDjMenu } = await import('./menuState');

let root: Root;
let host: HTMLElement;
let seen: boolean[];
let colors: number[][][];
function Probe() {
  seen.push(useDjScreen());
  colors.push(useDjMeshColors(true));
  return null;
}
const playing = (videoId: string) => usePlayer.setState({ nowPlaying: { videoId, title: 't', artist: 'a', artworkUrl: null } as never });

beforeEach(async () => {
  vi.useFakeTimers();
  seen = [];
  colors = [];
  host = document.createElement('div');
  document.body.appendChild(host);
  root = createRoot(host);
  usePlayer.setState({ nowPlaying: null });
  useDj.setState({ phase: 'idle', pending: false });
  await act(async () => root.render(createElement(Probe)));
});
afterEach(async () => {
  await act(async () => root.unmount());
  host.remove();
  vi.useRealTimers();
});
const now = () => seen[seen.length - 1];

describe('useDjScreen: the DJ speaks, then a song plays, then the DJ speaks again', () => {
  it('follows what is playing while the DJ is on', async () => {
    await act(async () => useDj.setState({ phase: 'on' }));
    await act(async () => playing(clipTrackId('dj-1-1')));
    expect(now()).toBe(true);
    await act(async () => playing('abcdefghijk'));
    expect(now()).toBe(false);
    await act(async () => playing(clipTrackId('dj-1-2')));
    expect(now()).toBe(true);
  });
  it('shows for a pending request over a song, and goes when it settles', async () => {
    await act(async () => useDj.setState({ phase: 'on' }));
    await act(async () => playing('abcdefghijk'));
    await act(async () => useDj.setState({ pending: true }));
    expect(now()).toBe(true);
    await act(async () => useDj.setState({ pending: false }));
    expect(now()).toBe(false);
  });
  it('stays up while the DJ menu is open, so a song starting does not take the field being typed in', async () => {
    await act(async () => useDj.setState({ phase: 'on' }));
    await act(async () => playing(clipTrackId('dj-1-1')));
    await act(async () => useDjMenu.setState({ open: true }));
    await act(async () => playing('abcdefghijk'));
    expect(now()).toBe(true);
    await act(async () => useDjMenu.setState({ open: false }));
    expect(now()).toBe(false);
  });
  it('is not up for a clip once the DJ is off', async () => {
    await act(async () => playing(clipTrackId('dj-1-1')));
    expect(now()).toBe(false);
  });
});

describe('useDjMeshColors', () => {
  it('steps to the next palette every MESH_CYCLE_MS', async () => {
    expect(colors[colors.length - 1]).toEqual(paletteCells(paletteAt(0, false)));
    await act(async () => vi.advanceTimersByTime(MESH_CYCLE_MS - 1));
    expect(colors[colors.length - 1]).toEqual(paletteCells(paletteAt(0, false)));
    await act(async () => vi.advanceTimersByTime(1));
    expect(colors[colors.length - 1]).toEqual(paletteCells(paletteAt(1, false)));
  });
});
