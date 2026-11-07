/**
 * requestAnimationFrame with a way out: some in-app browsers (and test shells) have none, and a canvas that waits on it
 * would simply never draw. The fallback ticks at about 60 Hz on a timer.
 */
export function requestFrame(callback: (now: number) => void): number {
  if (typeof requestAnimationFrame === 'function') return requestAnimationFrame(callback);
  return setTimeout(() => callback(performance.now()), 16) as unknown as number;
}

export function cancelFrame(id: number): void {
  if (typeof cancelAnimationFrame === 'function') cancelAnimationFrame(id);
  else clearTimeout(id);
}
