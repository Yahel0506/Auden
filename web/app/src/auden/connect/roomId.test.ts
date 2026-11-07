import { beforeEach, describe, expect, it, vi } from 'vitest';

let auth: unknown;
vi.mock('@/extension/useYtAuth', () => ({ ytAuthSnapshot: () => auth }));
const { connectRoomId } = await import('@/party/listenTogether');

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

  it('is null when signed out or when the email is unknown', async () => {
    auth = { signedIn: false, active: null, accounts: [] };
    expect(await connectRoomId()).toBeNull();
    auth = signedIn(null);
    expect(await connectRoomId()).toBeNull();
  });
});
