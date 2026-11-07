import { BridgeCallError } from '@bitchord/shared/bridge';
import { describe, expect, it, vi } from 'vitest';
import { needsSignIn, spotifyFailureText, toSpotifyFailure } from './failure';
import { DEFAULT_RETRY_AFTER_SEC, MAX_WAIT_MS, withSpotifyRetry } from './retry';

describe('toSpotifyFailure', () => {
  it('believes an extension that says what happened, with its Retry-After', () => {
    const f = toSpotifyFailure(new BridgeCallError('internal', 'Spotify fetchPlaylist failed (429)', { kind: 'rate_limited', retryAfterSec: 5 }));
    expect(f).toMatchObject({ kind: 'rate_limited', retryAfterSec: 5 });
  });

  it('reads an older extension\'s code and message', () => {
    expect(toSpotifyFailure(new BridgeCallError('unauthenticated', 'Not signed in to Spotify in this browser')).kind).toBe('signed_out');
    expect(toSpotifyFailure(new BridgeCallError('unauthenticated', 'Your Spotify session has expired. Sign in again')).kind).toBe('expired');
    expect(toSpotifyFailure(new BridgeCallError('network', 'x')).kind).toBe('network');
    expect(toSpotifyFailure(new BridgeCallError('timeout', 'x')).kind).toBe('network');
    expect(toSpotifyFailure(new BridgeCallError('internal', 'Spotify libraryV3 failed (429)')).kind).toBe('rate_limited');
    expect(toSpotifyFailure(new BridgeCallError('internal', 'Spotify libraryV3 failed (401)')).kind).toBe('expired');
    expect(toSpotifyFailure(new BridgeCallError('internal', 'Spotify libraryV3 failed (500)')).kind).toBe('unknown');
  });

  it('recognises a bridge error by its name when it comes from another copy of the module', () => {
    const foreign = Object.assign(new Error('Spotify fetchPlaylist failed (429)'), { name: 'BridgeCallError', code: 'internal', detail: { kind: 'rate_limited', retryAfterSec: 4 } });
    expect(toSpotifyFailure(foreign)).toMatchObject({ kind: 'rate_limited', retryAfterSec: 4 });
  });

  it('treats a fetch failure as the network and anything else as unknown', () => {
    expect(toSpotifyFailure(new TypeError('Failed to fetch')).kind).toBe('network');
    expect(toSpotifyFailure(new Error('boom')).kind).toBe('unknown');
    expect(toSpotifyFailure('weird').kind).toBe('unknown');
  });

  it('speaks in the phone\'s words and sends only the signed-out kinds to sign in', () => {
    expect(spotifyFailureText('rate_limited')).toBe('Spotify asked to slow down. Try again in a moment.');
    expect(spotifyFailureText('network')).toBe('Couldn’t reach Spotify. Check your connection.');
    expect(needsSignIn('signed_out') && needsSignIn('expired')).toBe(true);
    expect(needsSignIn('rate_limited') || needsSignIn('network') || needsSignIn('unknown')).toBe(false);
  });
});

describe('withSpotifyRetry', () => {
  const rate = (sec?: number) => new BridgeCallError('internal', 'x', { kind: 'rate_limited', ...(sec !== undefined && { retryAfterSec: sec }) });
  const dropped = () => new BridgeCallError('network', 'x');
  const run = (call: () => Promise<unknown>) => {
    const sleep = vi.fn(async (_ms: number, _signal?: AbortSignal) => {});
    return { sleep, result: withSpotifyRetry(call, { sleep }) };
  };

  it('returns at once when the call works', async () => {
    const { sleep, result } = run(async () => 'ok');
    expect(await result).toBe('ok');
    expect(sleep).not.toHaveBeenCalled();
  });

  it('waits Retry-After on a rate limit, twice at most, then gives up', async () => {
    const call = vi.fn(async () => { throw rate(3); });
    const { sleep, result } = run(call);
    await expect(result).rejects.toBeInstanceOf(BridgeCallError);
    expect(call).toHaveBeenCalledTimes(3);
    expect(sleep.mock.calls.map((c) => c[0])).toEqual([3000, 3000]);
  });

  it('waits two seconds when Spotify gave no Retry-After', async () => {
    let n = 0;
    const { sleep, result } = run(async () => { if (n++ === 0) throw rate(); return 'ok'; });
    expect(await result).toBe('ok');
    expect(sleep).toHaveBeenCalledWith(DEFAULT_RETRY_AFTER_SEC * 1000, undefined);
  });

  it('does not wait out a Retry-After longer than the cap: it surfaces it', async () => {
    const call = vi.fn(async () => { throw rate(MAX_WAIT_MS / 1000 + 1); });
    const { sleep, result } = run(call);
    await expect(result).rejects.toMatchObject({ detail: { retryAfterSec: 9 } });
    expect(call).toHaveBeenCalledTimes(1);
    expect(sleep).not.toHaveBeenCalled();
  });

  it('retries a dropped connection after one second and then two', async () => {
    const call = vi.fn(async () => { throw dropped(); });
    const { sleep, result } = run(call);
    await expect(result).rejects.toBeInstanceOf(BridgeCallError);
    expect(call).toHaveBeenCalledTimes(3);
    expect(sleep.mock.calls.map((c) => c[0])).toEqual([1000, 2000]);
  });

  it('never retries what waiting cannot fix', async () => {
    for (const error of [new BridgeCallError('unauthenticated', 'expired'), new BridgeCallError('internal', 'Spotify x failed (500)'), new Error('parser')]) {
      const call = vi.fn(async () => { throw error; });
      const { result } = run(call);
      await expect(result).rejects.toBe(error);
      expect(call).toHaveBeenCalledTimes(1);
    }
  });

  it('counts rate limits and dropped connections separately', async () => {
    const errors = [rate(1), dropped(), rate(1), dropped()];
    let i = 0;
    const { result } = run(async () => { if (i < errors.length) throw errors[i++]; return 'ok'; });
    expect(await result).toBe('ok');
  });

  it('stops waiting when aborted', async () => {
    const abort = new AbortController();
    const call = vi.fn(async () => { throw dropped(); });
    const promise = withSpotifyRetry(call, { signal: abort.signal });
    abort.abort();
    await expect(promise).rejects.toBeDefined();
    expect(call.mock.calls.length).toBeLessThanOrEqual(2);
  });
});
