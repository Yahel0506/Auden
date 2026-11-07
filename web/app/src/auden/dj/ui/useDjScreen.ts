import { useEffect, useMemo, useState, useSyncExternalStore } from 'react';
import type { Rgb } from '@/lib/meshPalette';
import { clipIdOf } from '@/playback/source';
import { usePlayer } from '@/state/player';
import { useDj } from '../session';
import { useDjMenu } from './menuState';
import { MESH_CYCLE_MS, isDjScreen, paletteAt, paletteCells } from './djScreen';

/** Whether the DJ's own screen is up now (see isDjScreen): the one answer the player, its mesh and its chrome all follow. */
export function useDjScreen(): boolean {
  const phase = useDj((s) => s.phase);
  const pending = useDj((s) => s.pending);
  const clipPlaying = usePlayer((s) => (s.nowPlaying ? clipIdOf(s.nowPlaying.videoId) !== null : null));
  const menuOpen = useDjMenu((s) => s.open);
  return isDjScreen({ phase, pending, clipPlaying, menuOpen });
}

const reduced = typeof matchMedia === 'function' ? matchMedia('(prefers-reduced-motion: reduce)') : null;
function useReducedMotion(): boolean {
  return useSyncExternalStore(
    (onChange) => {
      reduced?.addEventListener?.('change', onChange);
      return () => reduced?.removeEventListener?.('change', onChange);
    },
    () => reduced?.matches ?? false,
  );
}

/**
 * The mesh colours of the DJ's palettes, one after another every MESH_CYCLE_MS while [running] (Android's
 * cyclingMeshPalette): it holds the palette it is on while not running and carries on from there. With "reduce animation"
 * it stays on the first.
 */
export function useDjMeshColors(running = true): Rgb[] {
  const still = useReducedMotion();
  const [step, setStep] = useState(0);
  useEffect(() => {
    if (!running || still) return;
    const id = setInterval(() => setStep((n) => n + 1), MESH_CYCLE_MS);
    return () => clearInterval(id);
  }, [running, still]);
  return useMemo(() => paletteCells(paletteAt(step, still)), [step, still]);
}
