import { BridgeCallError, type SpotifyErrorKind } from '@bitchord/shared/bridge';

/**
 * How a Spotify call failed, as the page reasons about it (SpotifyException.kt). An extension that
 * says so (`detail.kind`) is believed; an older one sent only a code and a message, which are read
 * for what they are worth.
 */
export class SpotifyFailure extends Error {
  constructor(
    readonly kind: SpotifyErrorKind,
    message: string,
    /** Spotify's own Retry-After, in seconds, when it gave one. */
    readonly retryAfterSec?: number,
  ) {
    super(message);
    this.name = 'SpotifyFailure';
  }
}

/** By name as well as by class: a second copy of the module (a worker, hot reload) makes its errors fail `instanceof`. */
const isBridgeCallError = (error: unknown): error is BridgeCallError =>
  error instanceof BridgeCallError || (error instanceof Error && error.name === 'BridgeCallError');

export function toSpotifyFailure(error: unknown): SpotifyFailure {
  if (error instanceof SpotifyFailure) return error;
  if (isBridgeCallError(error)) {
    if (error.detail) return new SpotifyFailure(error.detail.kind, error.message, error.detail.retryAfterSec);
    if (error.code === 'unauthenticated') {
      return new SpotifyFailure(/expired/i.test(error.message) ? 'expired' : 'signed_out', error.message);
    }
    if (error.code === 'network' || error.code === 'timeout') return new SpotifyFailure('network', error.message);
    // An older extension says only "Spotify <operation> failed (<status>)".
    const status = /failed \((\d{3})\)$/.exec(error.message)?.[1];
    if (status === '429') return new SpotifyFailure('rate_limited', error.message);
    if (status === '401') return new SpotifyFailure('expired', error.message);
    return new SpotifyFailure('unknown', error.message);
  }
  if (error instanceof TypeError) return new SpotifyFailure('network', error.message);
  return new SpotifyFailure('unknown', error instanceof Error ? error.message : String(error));
}

/** What to tell the listener, in the phone's words (strings.xml spotify_err_*). */
export function spotifyFailureText(kind: SpotifyErrorKind): string {
  switch (kind) {
    case 'signed_out':
      return 'Connect your Spotify account to see your library';
    case 'expired':
      return 'Your Spotify session expired. Sign in again.';
    case 'rate_limited':
      return 'Spotify asked to slow down. Try again in a moment.';
    case 'network':
      return 'Couldn’t reach Spotify. Check your connection.';
    case 'unknown':
      return 'Something went wrong with Spotify.';
  }
}

/** The kinds only signing in again fixes; the rest are worth a Retry. */
export const needsSignIn = (kind: SpotifyErrorKind): boolean => kind === 'signed_out' || kind === 'expired';
