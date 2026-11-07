import { create } from 'zustand';
import { persist } from 'zustand/middleware';
import { playheadMs, type DeviceEntry, type PlaybackState } from '@bitchord/shared/connect';
import type { ConnectStatus } from './client';

/** Settings › Devices: whether this browser joins the account's device list at all. */
export const useConnectSettings = create<{ enabled: boolean; setEnabled: (on: boolean) => void }>()(
  persist((set) => ({ enabled: true, setEnabled: (enabled) => set({ enabled }) }), { name: 'auden:connect' }),
);

/** What another device is playing, as it last said so, and the server time it said it. */
export interface RemoteSession {
  state: PlaybackState;
  atServerMs: number;
}

interface ConnectState {
  status: ConnectStatus;
  /** This browser's device id (the one in the list). */
  myId: string;
  devices: DeviceEntry[];
  activeId: string | null;
  /** Another device's playback, while it is the active one (the player mirrors it); null when this browser is, or nothing is. */
  remote: RemoteSession | null;
  /** serverNow - localNow, from the welcome. */
  offsetMs: number;
}

export const useConnect = create<ConnectState>()(() => ({
  status: 'off',
  myId: '',
  devices: [],
  activeId: null,
  remote: null,
  offsetMs: 0,
}));

/** Where the remote song is now, in seconds, by the server's clock. */
export function remotePlayheadSec(s: ConnectState = useConnect.getState()): number {
  if (!s.remote) return 0;
  const track = s.remote.state.queue[s.remote.state.index];
  return playheadMs(s.remote.state, s.remote.atServerMs, Date.now() + s.offsetMs, track?.durationSec ? track.durationSec * 1000 : undefined) / 1000;
}
