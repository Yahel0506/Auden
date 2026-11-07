/**
 * A load that another load (or a stop) replaced. Both engines throw it, and
 * the deck that owns them (playback/deck.ts) does too; callers drop it
 * silently. Kept out of the YouTube engine's module so code that never loads
 * Shaka can still make and recognise one.
 */
export class SupersededError extends Error {
  override readonly name = 'SupersededError';
  constructor(message = 'Replaced by a newer load') {
    super(message);
  }
}

/** By name, so it holds across module copies and for errors made before this class existed. */
export function isSuperseded(error: unknown): boolean {
  return error instanceof Error && error.name === 'SupersededError';
}
