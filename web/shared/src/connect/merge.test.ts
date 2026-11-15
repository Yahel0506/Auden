import { describe, expect, it } from 'vitest';
import type { DeviceEntry, PlaybackState, ServerMessage } from './protocol';
import { RoomMerger } from './merge';

const dev = (deviceId: string, online = true): DeviceEntry => ({ deviceId, name: deviceId, platform: 'web', online });
const state = { isPlaying: true } as unknown as PlaybackState;
const welcome = (devices: DeviceEntry[], activeDeviceId: string | null, withState = false): ServerMessage => ({
  type: 'welcome', serverMs: 1, devices, activeDeviceId, state: withState ? state : null, stateServerMs: 1,
});
const devices = (m: ServerMessage[]) => (m[0] as Extract<ServerMessage, { type: 'welcome' | 'devices' }>).devices.map((d) => d.deviceId);

describe('RoomMerger', () => {
  it('lists every room’s devices once', () => {
    const merger = new RoomMerger('me');
    expect(devices(merger.feed(0, welcome([dev('me'), dev('a')], null)))).toEqual(['me', 'a']);
    const second = merger.feed(1, welcome([dev('me'), dev('a'), dev('b')], null));
    expect(second[0]!.type).toBe('devices');
    expect(devices(second)).toEqual(['me', 'a', 'b']);
  });

  it('is online in the merged list when any room has it online', () => {
    const merger = new RoomMerger('me');
    merger.feed(0, welcome([dev('me'), dev('a', false)], null));
    expect(devices(merger.feed(1, welcome([dev('a', true)], null)))).toEqual(['me', 'a']);
    const m = merger.feed(1, { type: 'devices', serverMs: 2, devices: [dev('a', true)], activeDeviceId: null });
    expect((m[0] as { devices: DeviceEntry[] }).devices.find((d) => d.deviceId === 'a')!.online).toBe(true);
  });

  it('makes this device the active one when any room says so', () => {
    const merger = new RoomMerger('me');
    merger.feed(0, welcome([dev('me'), dev('a')], 'a'));
    const m = merger.feed(1, { type: 'devices', serverMs: 2, devices: [dev('me')], activeDeviceId: 'me' });
    expect((m[0] as { activeDeviceId: string | null }).activeDeviceId).toBe('me');
  });

  it('passes on a state only from a room whose active device is the chosen one', () => {
    const merger = new RoomMerger('me');
    merger.feed(0, welcome([dev('me'), dev('a')], 'a'));
    merger.feed(1, welcome([dev('me'), dev('b')], 'b'));
    // The first room to speak decides whose story it is.
    expect(merger.feed(0, { type: 'state', serverMs: 3, from: 'a', state })).toHaveLength(1);
    expect(merger.feed(1, { type: 'state', serverMs: 4, from: 'b', state })).toEqual([]);
    expect(merger.feed(0, { type: 'state', serverMs: 5, from: 'a', state })).toHaveLength(1);
  });

  it('ignores its own state echoed back', () => {
    const merger = new RoomMerger('me');
    merger.feed(0, welcome([dev('me')], 'me'));
    expect(merger.feed(0, { type: 'state', serverMs: 2, from: 'me', state })).toEqual([]);
  });

  it('passes on a takeover that two rooms both send only once', () => {
    const merger = new RoomMerger('me');
    const takeover: ServerMessage = { type: 'takeover', serverMs: 1, state: null, play: true };
    expect(merger.feed(0, takeover, 1000)).toHaveLength(1);
    expect(merger.feed(1, takeover, 1200)).toEqual([]);
    expect(merger.feed(1, takeover, 3000)).toHaveLength(1);
  });

  it('forgets a room that went away', () => {
    const merger = new RoomMerger('me');
    merger.feed(0, welcome([dev('me'), dev('a')], null));
    merger.feed(1, welcome([dev('me'), dev('b')], null));
    const dropped = merger.drop(1) as { devices: DeviceEntry[] };
    expect(dropped.devices.map((d) => d.deviceId)).toEqual(['me', 'a']);
    expect(merger.drop(0)).toBeNull();
  });
});
