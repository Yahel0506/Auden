import { beforeEach, describe, expect, it, vi } from 'vitest';

let auth: unknown;
let spotify: unknown = undefined;
vi.mock('@/lib/queryClient', () => ({ queryClient: { getQueryData: () => spotify } }));
vi.mock('@/extension/useYtAuth', () => ({ ytAuthSnapshot: () => auth }));
const { connectRoomId } = await import('@/party/listenTogether');

const SPOTIFY_SOMEUSER = '1ca227dbebe11111ec5325bdc6250731';
const LOCAL_YAEL = 'fce7ecee6ca56cd4b6f7e11bab1fba74';
const LOCAL_GUEST = 'e8646db4ea2016f2f1747ef3303a2047';

const signedIn = (email: string | null, handle: string | null = null) => ({
  signedIn: true,
  active: { id: 'c', authUser: '1', handle },
  accounts: [{ authUser: '0', email: 'other@example.com' }, { authUser: '1', email }],
});

describe('connectRoomId by handle', () => {
  it('prefers the active channel’s @handle, which the phone reads from the same place', async () => {
    auth = signedIn(null, '@SomeChannel');
    // Kotlin: ListenTogether.connectRoomIdFor(handle = "@SomeChannel", email = "") (ConnectRoomIdTest).
    expect(await connectRoomId()).toBe('d0695fb667171a671dc9e9c054acfcd7');
    auth = signedIn('person@example.com', '@SomeChannel');
    expect(await connectRoomId()).toBe('d0695fb667171a671dc9e9c054acfcd7');
  });

  it('ignores a "handle" that is really the email fallback', async () => {
    auth = signedIn('Person@Example.com', 'person@example.com');
    expect(await connectRoomId()).toBe('2c33f5f9e51c05093f360ef8f4dde494');
  });
});

describe('connectRoomId', () => {
  beforeEach(() => (auth = signedIn('Person@Example.com ')));

  it('hashes the login email, case and spaces aside, to the value the phone computes', async () => {
    // Kotlin: ListenTogether.connectRoomIdFor("person@example.com") == the same 32 digits (ConnectRoomIdTest).
    expect(await connectRoomId()).toBe('2c33f5f9e51c05093f360ef8f4dde494');
  });

  it('uses the email of the login the active channel belongs to', async () => {
    auth = { ...signedIn('person@example.com'), active: { id: 'c', authUser: '0' } };
    expect(await connectRoomId()).not.toBe('2c33f5f9e51c05093f360ef8f4dde494');
  });

  it('is the local room when signed out or when the email is unknown', async () => {
    auth = { signedIn: false, active: null, accounts: [] };
    expect(await connectRoomId()).toMatch(/^local\/[0-9a-f]{32}$/);
    auth = signedIn(null);
    expect(await connectRoomId()).toMatch(/^local\/[0-9a-f]{32}$/);
  });
});

describe('local room', () => {
  const store = new Map<string, string>();
  beforeEach(() => {
    auth = { signedIn: false, active: null, accounts: [] };
    store.clear();
    vi.stubGlobal('localStorage', {
      getItem: (k: string) => store.get(k) ?? null,
      setItem: (k: string, v: string) => void store.set(k, v),
    });
  });

  it('is the hash of the party name, lower-cased and trimmed, with the local prefix', async () => {
    // Kotlin: ListenTogether.localRoomIdFor("yael") (ConnectRoomIdTest).
    localStorage.setItem('bitchord:party', JSON.stringify({ nickname: ' Yael ' }));
    expect(await connectRoomId()).toBe('local/' + LOCAL_YAEL);
  });

  it('falls back to a shared "guest" name when no name was chosen', async () => {
    expect(await connectRoomId()).toBe('local/' + LOCAL_GUEST);
  });

  it('is the Spotify account’s room when there is no Google one', async () => {
    // Kotlin: ListenTogether.spotifyRoomIdFor("SomeUser") (ConnectRoomIdTest).
    spotify = { signedIn: true, user: { name: 'n', id: 'SomeUser' } };
    expect(await connectRoomId()).toBe(SPOTIFY_SOMEUSER);
    spotify = undefined;
  });

  it('keeps an account’s room when there is one', async () => {
    auth = signedIn('Person@Example.com ');
    expect(await connectRoomId()).toBe('2c33f5f9e51c05093f360ef8f4dde494');
  });
});
