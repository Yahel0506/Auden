import { Laptop, MonitorSpeaker, Smartphone, Tv, Watch } from 'lucide-react';
import { useEffect, useRef, useState, type ReactNode } from 'react';
import type { ConnectPlatform } from '@bitchord/shared/connect';
import { config } from '@/config';
import { switchTo } from '../session';
import { useConnect, useConnectSettings } from '../store';

const ICONS: Record<ConnectPlatform, ReactNode> = {
  web: <Laptop size={18} />,
  pc: <Laptop size={18} />,
  android: <Smartphone size={18} />,
  tv: <Tv size={18} />,
  watch: <Watch size={18} />,
};

/**
 * The Audio Output button: this browser and every other device of the account that has Auden open right now,
 * as Spotify's "Connect to a device". Picking one plays there (session.ts switchTo); the list is live.
 */
export function DevicePicker({ className, placement = 'above-end' }: { className?: string; placement?: 'above-end' | 'above-start' }) {
  const enabled = useConnectSettings((s) => s.enabled);
  const status = useConnect((s) => s.status);
  const devices = useConnect((s) => s.devices);
  const activeId = useConnect((s) => s.activeId);
  const myId = useConnect((s) => s.myId);
  const [open, setOpen] = useState(false);
  const root = useRef<HTMLDivElement>(null);

  useEffect(() => {
    if (!open) return;
    const onDown = (e: PointerEvent) => {
      if (!root.current?.contains(e.target as Node)) setOpen(false);
    };
    const onKey = (e: KeyboardEvent) => e.key === 'Escape' && setOpen(false);
    document.addEventListener('pointerdown', onDown);
    document.addEventListener('keydown', onKey);
    return () => {
      document.removeEventListener('pointerdown', onDown);
      document.removeEventListener('keydown', onKey);
    };
  }, [open]);

  if (!config.connectUrl || !enabled) return null;
  const online = devices.filter((d) => d.online);
  // Anywhere but this browser: that is where the sound is.
  const elsewhere = activeId !== null && activeId !== myId;
  const mine = online.find((d) => d.deviceId === myId);
  const others = online.filter((d) => d.deviceId !== myId);

  return (
    <div ref={root} className="relative">
      <button
        className={className}
        aria-label="Audio output"
        aria-haspopup="menu"
        aria-expanded={open}
        aria-pressed={elsewhere}
        onClick={() => setOpen((o) => !o)}
      >
        <MonitorSpeaker size={18} />
      </button>
      {open && (
        <div
          role="menu"
          className={`glass-pill absolute bottom-full z-50 mb-2 w-[min(300px,86vw)] rounded-[14px] p-2 text-start ${placement === 'above-end' ? 'right-0' : 'left-0'}`}
        >
          <div className="px-2 pt-1 pb-2 type-body-emphasized">Connect to a device</div>
          {status !== 'online' && <div className="px-2 pb-2 text-secondary">{status === 'connecting' ? 'Connecting…' : 'Not connected'}</div>}
          {status === 'online' && (
            <ul className="flex flex-col">
              {mine && <Row id={mine.deviceId} name="This browser" subtitle={mine.name} platform={mine.platform} active={activeId === myId} onPick={() => setOpen(false)} />}
              {others.map((d) => (
                <Row key={d.deviceId} id={d.deviceId} name={d.name} subtitle={d.platform === 'android' ? 'Auden for Android' : d.platform === 'tv' ? 'Auden for TV' : 'Auden'} platform={d.platform} active={activeId === d.deviceId} onPick={() => setOpen(false)} />
              ))}
            </ul>
          )}
          {status === 'online' && others.length === 0 && <div className="px-2 pt-1 pb-1 text-secondary">Open Auden on another device with this account and it shows up here.</div>}
        </div>
      )}
    </div>
  );
}

function Row({ id, name, subtitle, platform, active, onPick }: { id: string; name: string; subtitle: string; platform: ConnectPlatform; active: boolean; onPick: () => void }) {
  return (
    <li>
      <button
        role="menuitemradio"
        aria-checked={active}
        className={`flex w-full items-center gap-3 rounded-[8px] px-2 py-2 text-start hover:bg-fill-hover ${active ? 'text-key' : 'text-primary'}`}
        onClick={() => {
          switchTo(id);
          onPick();
        }}
      >
        <span className="shrink-0">{ICONS[platform]}</span>
        <span className="min-w-0 flex-1">
          <span className="block truncate type-body-emphasized">{name}</span>
          <span className="block truncate text-secondary">{active ? `Playing on ${name}` : subtitle}</span>
        </span>
      </button>
    </li>
  );
}
