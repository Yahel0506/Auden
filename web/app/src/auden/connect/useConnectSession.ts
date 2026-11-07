import { useEffect } from 'react';
import { useYtAuth } from '@/extension/useYtAuth';
import { startConnect } from './session';
import { useConnectSettings } from './store';

/** Keeps this browser in the account's device list while it is signed in and the setting is on. */
export function useConnectSession(): void {
  const auth = useYtAuth();
  const enabled = useConnectSettings((s) => s.enabled);
  const accountId = auth.state === 'ready' && auth.auth.signedIn ? auth.auth.active?.id : null;
  useEffect(() => {
    if (!enabled || !accountId) return;
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
  }, [enabled, accountId]);
}
