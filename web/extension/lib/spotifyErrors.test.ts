import type { SpotifyAuthState } from '@bitchord/shared/bridge';
import { describe, expect, it } from 'vitest';
import { Cooldown, cookieKeyOf, DEFAULT_RETRY_AFTER_SEC, kindOf, MAX_COOLDOWN_SEC, problemState, rememberedFor, retryAfterOf, SpotifyAuthError, SpotifyError } from './spotifyErrors';

const headers = (value: string | null) => ({ headers: { get: () => value } });
const base: SpotifyAuthState = { browserSignedIn: true, connected: true, signedIn: false, user: null, problem: null };
const ana = { name: 'Ana', avatarUrl: null, id: 'ana123' };

describe('kindOf', () => {
  it('reads a typed error, a dropped fetch and anything else', () => {
    expect(kindOf(new SpotifyError('rate_limited', 'x', 5))).toBe('rate_limited');
    expect(kindOf(new SpotifyAuthError('x'))).toBe('signed_out');
    expect(kindOf(new SpotifyAuthError('x', 'expired'))).toBe('expired');
    expect(kindOf(new TypeError('Failed to fetch'))).toBe('network');
    expect(kindOf(new Error('parser bug'))).toBe('unknown');
  });

  it('keeps the old code on an auth error and carries Retry-After on a rate limit', () => {
    expect(new SpotifyAuthError('x').code).toBe('unauthenticated');
    expect(new SpotifyError('rate_limited', 'x', 7).retryAfterSec).toBe(7);
  });
});

describe('retryAfterOf', () => {
  it('rounds up what Spotify says and falls back to two seconds', () => {
    expect(retryAfterOf(headers('3'))).toBe(3);
    expect(retryAfterOf(headers('1.2'))).toBe(2);
    expect(retryAfterOf(headers(null))).toBe(DEFAULT_RETRY_AFTER_SEC);
    expect(retryAfterOf(headers('soon'))).toBe(DEFAULT_RETRY_AFTER_SEC);
    expect(retryAfterOf(headers('-4'))).toBe(DEFAULT_RETRY_AFTER_SEC);
  });
});

describe('Cooldown', () => {
  it('fails fast until the Retry-After has passed, and no longer than the cap', () => {
    const cooldown = new Cooldown();
    expect(cooldown.remainingSec(1000)).toBe(0);
    cooldown.start(5, 1000);
    expect(cooldown.remainingSec(1000)).toBe(5);
    expect(cooldown.remainingSec(3500)).toBe(3);
    expect(cooldown.remainingSec(6001)).toBe(0);
    cooldown.start(10_000, 0);
    expect(cooldown.remainingSec(0)).toBe(MAX_COOLDOWN_SEC);
    cooldown.reset();
    expect(cooldown.remainingSec(0)).toBe(0);
  });
});

describe('problemState', () => {
  it('keeps the account that was read through a throttle or a dropped connection', () => {
    for (const error of [new SpotifyError('rate_limited', 'slow down', 3), new TypeError('offline'), new SpotifyError('network', 'offline')]) {
      const state = problemState(base, ana, error);
      expect(state).toMatchObject({ signedIn: true, user: ana });
      expect(state.problemKind).toBe(kindOf(error));
    }
  });

  it('does not invent an account: with none read before, a throttle leaves it signed out', () => {
    expect(problemState(base, null, new SpotifyError('rate_limited', 'x'))).toMatchObject({ signedIn: false, user: null, problemKind: 'rate_limited' });
  });

  it('signs out for what really is a sign-out, expiry or an unknown failure', () => {
    for (const error of [new SpotifyAuthError('x'), new SpotifyAuthError('x', 'expired'), new SpotifyError('unknown', 'x'), new Error('parser')]) {
      expect(problemState(base, ana, error)).toMatchObject({ signedIn: false, user: null });
    }
  });
});

describe('the remembered account', () => {
  it('is used only under the cookie it was read with', async () => {
    const key = await cookieKeyOf('sp_dc_one');
    const stored = { cookieKey: key, user: ana };
    expect(rememberedFor(stored, key)).toBe(ana);
    expect(rememberedFor(stored, await cookieKeyOf('sp_dc_two'))).toBeNull();
    expect(rememberedFor(null, key)).toBeNull();
  });

  it('digests the cookie: what is kept never contains it', async () => {
    const key = await cookieKeyOf('AQB-secret-cookie');
    expect(key).toMatch(/^[0-9a-f]{16}$/);
    expect(key).not.toContain('secret');
    expect(await cookieKeyOf('AQB-secret-cookie')).toBe(key);
  });

  it('is what keeps an account through a restart: no profile cache, a throttle, same cookie -> still signed in', async () => {
    const key = await cookieKeyOf('sp_dc_one');
    const user = rememberedFor({ cookieKey: key, user: ana }, key);
    expect(problemState(base, user, new SpotifyError('rate_limited', 'slow', 3))).toMatchObject({ signedIn: true, user: ana, problemKind: 'rate_limited' });
    // Under another sign-in's cookie it is not the same account, so nothing is kept.
    const other = rememberedFor({ cookieKey: key, user: ana }, await cookieKeyOf('sp_dc_two'));
    expect(problemState(base, other, new SpotifyError('rate_limited', 'slow', 3))).toMatchObject({ signedIn: false, user: null });
  });
});
