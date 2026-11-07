import { toSpotifyFailure } from './failure';

/**
 * SpotifyLibrary.withRetry, applied to ONE call (a page of a list), not the whole paged loop: a retry
 * asks for the same offset again, so nothing already read is read twice.
 *
 * Only what waiting can fix is retried: a rate limit (twice, after Spotify's Retry-After, two seconds
 * when it gave none) and a dropped connection (twice, after one second and then two). An expired
 * session is not: the extension has already re-minted its token once, so asking again at once gets the
 * same answer. A Retry-After longer than the cap is surfaced, not waited out: waiting eight seconds
 * and asking again would only be answered with another 429.
 */
export const RATE_LIMIT_RETRIES = 2;
export const NETWORK_RETRIES = 2;
export const DEFAULT_RETRY_AFTER_SEC = 2;
export const MAX_WAIT_MS = 8_000;
export const NETWORK_BACKOFF_MS = 1_000;

export interface RetryOptions {
  signal?: AbortSignal;
  /** Test seam. */
  sleep?: (ms: number, signal?: AbortSignal) => Promise<void>;
}

export function abortableSleep(ms: number, signal?: AbortSignal): Promise<void> {
  return new Promise((resolve, reject) => {
    if (signal?.aborted) return reject(signal.reason ?? new DOMException('Aborted', 'AbortError'));
    const timer = setTimeout(() => {
      signal?.removeEventListener('abort', onAbort);
      resolve();
    }, ms);
    const onAbort = () => {
      clearTimeout(timer);
      reject(signal?.reason ?? new DOMException('Aborted', 'AbortError'));
    };
    signal?.addEventListener('abort', onAbort, { once: true });
  });
}

export async function withSpotifyRetry<T>(call: () => Promise<T>, { signal, sleep = abortableSleep }: RetryOptions = {}): Promise<T> {
  let rateLimited = 0;
  let dropped = 0;
  for (;;) {
    try {
      return await call();
    } catch (error) {
      const failure = toSpotifyFailure(error);
      if (failure.kind === 'rate_limited' && rateLimited < RATE_LIMIT_RETRIES) {
        const waitMs = (failure.retryAfterSec ?? DEFAULT_RETRY_AFTER_SEC) * 1000;
        if (waitMs > MAX_WAIT_MS) throw error;
        rateLimited++;
        await sleep(waitMs, signal);
      } else if (failure.kind === 'network' && dropped < NETWORK_RETRIES) {
        await sleep(NETWORK_BACKOFF_MS * 2 ** dropped++, signal);
      } else {
        throw error;
      }
    }
  }
}
