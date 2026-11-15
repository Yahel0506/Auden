import { useEffect, useState } from 'react';
import { useSpotifyAuth } from '@/extension/useSpotifyAuth';
import { useYtAuth } from '@/extension/useYtAuth';
import { PARTY_SETTINGS_EVENT, partySettings } from '@/party/listenTogether';
import { startConnect } from './session';
import { useConnectSettings } from './store';

/**
 * Keeps this browser in its device list while the setting is on: the YouTube Music account's room when signed in,
 * else the Spotify account's, else — with no account at all — the room for the name used in parties, on this network.
 */
export function useConnectSession(): void {
  const auth = useYtAuth();
  const spotify = useSpotifyAuth();
  const enabled = useConnectSettings((s) => s.enabled);
  const [nickname, setNickname] = useState(() => partySettings().nickname.trim().toLowerCase());
  useEffect(() => {
    const onChange = () => setNickname(partySettings().nickname.trim().toLowerCase());
    window.addEventListener(PARTY_SETTINGS_EVENT, onChange);
    return () => window.removeEventListener(PARTY_SETTINGS_EVENT, onChange);
  }, []);
  // Not decided until the auth state has been read: starting early would join the local room, then jump.
  const ready = auth.state === 'ready' && spotify.state !== 'checking';
  const accountId = auth.state === 'ready' && auth.auth.signedIn ? auth.auth.active?.id : null;
  const spotifyId = spotify.state === 'ready' && spotify.auth.signedIn ? spotify.auth.user?.id?.trim().toLowerCase() : null;
  const roomKey = !ready ? null : accountId ? `account:${accountId}` : spotifyId ? `spotify:${spotifyId}` : `local:${nickname}`;
  useEffect(() => {
    if (!enabled || !roomKey) return;
    let stop: (() => void) | null = null;
    let cancelled = false;
    void startConnect().then((fn) => {
      if (cancelled) fn();
      else stop = fn;
    });
    return () => {
      cancelled = true;
      stop?.();
    };
  }, [enabled, roomKey]);
}
