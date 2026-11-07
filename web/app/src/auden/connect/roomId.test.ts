import { beforeEach, describe, expect, it, vi } from 'vitest';

let auth: unknown;
vi.mock('@/extension/useYtAuth', () => ({ ytAuthSnapshot: () => auth }));
const { connectRoomId } = await import('@/party/listenTogether');

const signedIn = (email: string | null) => ({
  signedIn: true,
  active: { id: 'c', authUser: '1' },
  accounts: [{ authUser: '0', email: 'other@example.com' }, { authUser: '1', email }],
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
