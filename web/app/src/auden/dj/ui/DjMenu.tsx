import { t as translateUi } from '@/i18n';
import { Sparkles } from 'lucide-react';
import { useEffect, useMemo, useState } from 'react';
import { Menu, MenuItem, MenuSeparator } from '@/components/Menu';
import { usePlayer } from '@/state/player';
import { loadDjPool } from '../loadPool';
import { lessOfThis, moreOfThis, startDj, steerDj, stopDj } from '../session';
import { createLongPress } from './longPress';
import { useDjMenu } from './menuState';

/** How long the DJ's corner button is held for "surprise me" (Android: a long press). */
export const LONG_PRESS_MS = 500;

/**
 * The DJ's button in the full-screen player (it takes the Up Next button's place while the DJ is on): a tap opens a
 * glass menu with a field to say what to hear and more / less / stop; a long press is "surprise me".
 */
export function DjButton({ className }: { className?: string }) {
  const [anchor, setAnchor] = useState<HTMLElement | null>(null);
  const [text, setText] = useState('');
  const press = useMemo(() => createLongPress(() => void steerDj(''), LONG_PRESS_MS), []);
  const close = () => setAnchor(null);
  // The DJ's screen stays under the menu while it is open (a song starting must not take the field being typed in).
  useEffect(() => {
    useDjMenu.setState({ open: anchor !== null });
    return () => useDjMenu.setState({ open: false });
  }, [anchor]);

  return (
    <>
      <button
        className={className}
        aria-label={translateUi("dj_menu.auden_dj")}
        aria-haspopup="menu"
        aria-expanded={anchor !== null}
        title={translateUi("dj_menu.tap_to_ask_the_dj")}
        onPointerDown={press.down}
        onPointerUp={press.cancel}
        onPointerLeave={press.cancel}
        onPointerCancel={press.cancel}
        onClick={(e) => {
          // The press that became a surprise is not also a tap.
          if (press.consumeLong()) return;
          setAnchor(e.currentTarget);
        }}
      >
        <Sparkles size={18} />
      </button>
      {anchor && (
        <Menu anchor={anchor} placement="above-end" onClose={close} label={translateUi("dj_menu.auden_dj")}>
          <form
            className="flex w-[min(280px,calc(100vw-48px))] gap-2 px-3 pt-2 pb-1.5"
            onSubmit={(e) => {
              e.preventDefault();
              const ask = text.trim();
              setText('');
              close();
              void (ask ? steerDj(ask) : startDj(loadDjPool, ''));
            }}
          >
            <input
              autoFocus
              value={text}
              onChange={(e) => setText(e.target.value)}
              placeholder={translateUi("dj_menu.what_do_you_want_to")}
              aria-label={translateUi("dj_menu.ask_the_dj")}
              maxLength={120}
              className="h-8 min-w-0 flex-1 rounded-lg bg-quinary px-3 type-callout text-primary outline-none placeholder:text-tertiary"
            />
            <button type="submit" className="type-callout-emphasized text-key">{translateUi("dj_menu.send")}</button>
          </form>
          <MenuSeparator />
          <MenuItem onSelect={() => (close(), moreOfThis())}>{translateUi("dj_menu.more_like_this")}</MenuItem>
          <MenuItem onSelect={() => (close(), lessOfThis(() => usePlayer.getState().next()))}>{translateUi("dj_menu.less_like_this")}</MenuItem>
          <MenuSeparator />
          <MenuItem destructive onSelect={() => (close(), stopDj())}>{translateUi("dj_menu.stop_the_dj")}</MenuItem>
        </Menu>
      )}
    </>
  );
}
