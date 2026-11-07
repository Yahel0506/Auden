// @vitest-environment happy-dom
import { beforeEach, describe, expect, it } from 'vitest';
import { usePlayerLook } from './playerLook';

beforeEach(() => localStorage.clear());

describe('player look', () => {
  it('starts on Continuation with framed covers', () => {
    expect(usePlayerLook.getState()).toMatchObject({ theme: 'continuation', fullBleedCovers: false });
  });
  it('keeps the theme across a reload, and falls back on one it does not know', async () => {
    usePlayerLook.getState().setTheme('blur');
    usePlayerLook.getState().setFullBleedCovers(true);
    const raw = localStorage.getItem('auden:look')!;
    usePlayerLook.setState({ theme: 'continuation', fullBleedCovers: false });
    localStorage.setItem('auden:look', raw);
    await usePlayerLook.persist.rehydrate();
    expect(usePlayerLook.getState()).toMatchObject({ theme: 'blur', fullBleedCovers: true });
    localStorage.setItem('auden:look', JSON.stringify({ state: { theme: 'glitter' }, version: 1 }));
    await usePlayerLook.persist.rehydrate();
    expect(usePlayerLook.getState()).toMatchObject({ theme: 'continuation', fullBleedCovers: false });
  });
});
