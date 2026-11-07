import type { SpotifyAuthState, SpotifyErrorKind } from '@bitchord/shared/bridge';

/**
 * How a Spotify call fails, kept apart from the code that makes the calls so it can be tested without a
 * browser: the page reads the kind (and Spotify's Retry-After) to decide what to say and whether to retry.
 */

/** A Spotify call that failed, and how (SpotifyException.kt). */
export class SpotifyError extends Error {
  constructor(
    readonly kind: SpotifyErrorKind,
    message: string,
    /** Spotify's own Retry-After, in seconds, when it gave one. */
    readonly retryAfterSec?: number,
  ) {
    super(message);
  }
}

/** Not signed in, disconnected, or the session has expired: the user has to sign in. */
export class SpotifyAuthError extends SpotifyError {
  readonly code = 'unauthenticated';
  constructor(message: string, kind: SpotifyErrorKind = 'signed_out') {
    super(kind, message);
  }
}

/** The kind of any error thrown while talking to Spotify. */
export function kindOf(error: unknown): SpotifyErrorKind {
  if (error instanceof SpotifyError) return error.kind;
  return error instanceof TypeError ? 'network' : 'unknown';
}

export const DEFAULT_RETRY_AFTER_SEC = 2;
export const MAX_COOLDOWN_SEC = 60;

/** Spotify's Retry-After in whole seconds; two when it gave none or something unreadable. */
export function retryAfterOf(response: { headers: { get(name: string): string | null } }): number {
  const seconds = Number(response.headers.get('retry-after'));
  return Number.isFinite(seconds) && seconds > 0 ? Math.ceil(seconds) : DEFAULT_RETRY_AFTER_SEC;
}

/**
 * After a 429, calls fail fast until Spotify's Retry-After has passed, so a throttled library does not
 * invite more requests from other tabs and from the canvas lookups that run during playback.
 */
export class Cooldown {
  private until = 0;

  start(seconds: number, now = Date.now()): void {
    this.until = now + Math.min(seconds, MAX_COOLDOWN_SEC) * 1000;
  }

  /** Whole seconds still to wait; 0 when none. */
  remainingSec(now = Date.now()): number {
    return Math.max(0, Math.ceil((this.until - now) / 1000));
  }

  reset(): void {
    this.until = 0;
  }
}

/**
 * What the auth state says when reading the profile failed. A throttle or a dropped connection is not a
 * sign-out: if an account was read before, it stays (and so does everything already shown for it).
 */
export function problemState(
  base: SpotifyAuthState,
  cached: SpotifyAuthState['user'],
  error: unknown,
): SpotifyAuthState {
  const problem = error instanceof Error ? error.message : String(error);
  const problemKind = kindOf(error);
  if (cached && (problemKind === 'network' || problemKind === 'rate_limited')) {
    return { ...base, signedIn: true, user: cached, problem, problemKind };
  }
  return { ...base, problem, problemKind };
}

/** The account last read, kept (without the cookie itself) so a transient failure after a service-worker restart still has one. */
export interface RememberedUser {
  /** A digest of the `sp_dc` it was read under: another sign-in is another account until it is read again. */
  cookieKey: string;
  user: NonNullable<SpotifyAuthState['user']>;
}

/** The remembered account, only if it was read under the cookie that is in the browser now. */
export function rememberedFor(stored: RememberedUser | null | undefined, cookieKey: string): RememberedUser['user'] | null {
  return stored && stored.cookieKey === cookieKey ? stored.user : null;
}

/** A short digest of the cookie, so what is kept never contains it. */
export async function cookieKeyOf(cookie: string): Promise<string> {
  const digest = await crypto.subtle.digest('SHA-256', new TextEncoder().encode(cookie));
  return [...new Uint8Array(digest).slice(0, 8)].map((b) => b.toString(16).padStart(2, '0')).join('');
}
