import { describe, expect, it } from 'vitest';
import { DJ_MESH_PALETTES, MESH_CYCLE_MS, isDjScreen, paletteAt, paletteCells, tuned } from './djScreen';

describe('isDjScreen', () => {
  it('is up while the DJ chooses, whatever is playing', () => {
    expect(isDjScreen({ phase: 'starting', pending: true, clipPlaying: false })).toBe(true);
    expect(isDjScreen({ phase: 'on', pending: true, clipPlaying: false })).toBe(true);
  });
  it('is up while its voice speaks and gone when a song plays', () => {
    expect(isDjScreen({ phase: 'on', pending: false, clipPlaying: true })).toBe(true);
    expect(isDjScreen({ phase: 'on', pending: false, clipPlaying: false })).toBe(false);
  });
  it('stays up under an open DJ menu even when a song starts, but never for a DJ that is off', () => {
    expect(isDjScreen({ phase: 'on', pending: false, clipPlaying: false, menuOpen: true })).toBe(true);
    expect(isDjScreen({ phase: 'idle', pending: false, clipPlaying: false, menuOpen: true })).toBe(false);
  });
  it('holds the place while the DJ is on and nothing has loaded yet, so the opening line never meets an empty player', () => {
    expect(isDjScreen({ phase: 'on', pending: false, clipPlaying: null })).toBe(true);
    expect(isDjScreen({ phase: 'idle', pending: false, clipPlaying: null })).toBe(false);
  });
  it('is never up for a DJ that is off, even over a clip left behind', () => {
    expect(isDjScreen({ phase: 'idle', pending: false, clipPlaying: true })).toBe(false);
    expect(isDjScreen({ phase: 'starting', pending: false, clipPlaying: true })).toBe(false);
  });
});

describe('the DJ mesh', () => {
  it('has the four palettes of four colours Android has, first to last', () => {
    expect(DJ_MESH_PALETTES).toHaveLength(4);
    expect(DJ_MESH_PALETTES.every((p) => p.length === 4)).toBe(true);
    expect(DJ_MESH_PALETTES[0]![0]).toEqual([0xff, 0x8f, 0xc4]);
    expect(DJ_MESH_PALETTES[3]![3]).toEqual([0xff, 0xb2, 0x7a]);
    expect(MESH_CYCLE_MS).toBe(3600);
  });
  it('steps through them and wraps; reduce-motion holds the first', () => {
    expect(paletteAt(0, false)).toBe(DJ_MESH_PALETTES[0]);
    expect(paletteAt(5, false)).toBe(DJ_MESH_PALETTES[1]);
    expect(paletteAt(5, true)).toBe(DJ_MESH_PALETTES[0]);
  });
  it('tunes colours as Android does: more saturated, lightness held between 0.28 and 0.58', () => {
    const [r, g, b] = tuned([255, 255, 255]);
    expect(r).toBe(g);
    expect(g).toBe(b);
    expect(Math.abs(r - 0.58 * 255)).toBeLessThanOrEqual(1);
    const dark = tuned([5, 5, 5]);
    expect(Math.abs(dark[0] - 0.28 * 255)).toBeLessThanOrEqual(1);
    // A dark red at lightness 0.3 and saturation 0.333 (102, 51, 51) is lifted to saturation 0.45: (111, 42, 42).
    const [pr, pg, pb] = tuned([102, 51, 51]);
    expect([pr, pg, pb].map((v) => Math.round(v))).toEqual([111, 42, 42]);
  });
  it('lays one colour in each quadrant of the 4x4 grid', () => {
    const cells = paletteCells(DJ_MESH_PALETTES[0]!);
    expect(cells).toHaveLength(16);
    expect(new Set(cells.map((c) => c.join())).size).toBe(4);
    // x = 3 - row, y = column: the top-left quadrant is the high rows and low columns.
    const at = (x: number, y: number) => cells[(3 - x) * 4 + y]!.join();
    expect(at(0, 0)).toBe(at(1, 1));
    expect(at(3, 0)).toBe(at(2, 1));
    expect(at(3, 3)).toBe(at(2, 2));
    expect(at(0, 3)).toBe(at(1, 2));
    expect(at(0, 0)).not.toBe(at(3, 0));
    // Android's corners: colour 0 top-left, 1 top-right, 2 bottom-right, 3 bottom-left.
    const palette = DJ_MESH_PALETTES[0]!;
    expect(at(0, 0)).toBe(tuned(palette[0]!).join());
    expect(at(3, 0)).toBe(tuned(palette[1]!).join());
    expect(at(3, 3)).toBe(tuned(palette[2]!).join());
    expect(at(0, 3)).toBe(tuned(palette[3]!).join());
  });
});
