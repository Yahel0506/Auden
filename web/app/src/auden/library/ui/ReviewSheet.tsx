import { useEffect, useRef } from 'react';
import { acceptSuggestion, rejectSuggestion } from '../identify/identifier';
import { useReviewRows } from './data';

/** Doubtful matches, one by one: accept the suggested YouTube Music song, or say it isn't it. */
export function ReviewSheet({ onClose }: { onClose: () => void }) {
  const { data: rows = [] } = useReviewRows();
  const dialog = useRef<HTMLDialogElement>(null);
  useEffect(() => {
    const d = dialog.current;
    if (d && !d.open) d.showModal();
  }, []);
  return (
    <dialog
      ref={dialog}
      onClose={onClose}
      className="m-auto w-[min(560px,calc(100vw-32px))] rounded-2xl bg-[var(--systemQuaternary,#222)] p-0 text-primary backdrop:bg-black/50"
      aria-label="Review matches"
    >
      <div className="flex items-center justify-between px-5 pt-4">
        <h2 className="type-title-3-emphasized">Review matches</h2>
        <button className="type-callout-emphasized text-key" onClick={() => dialog.current?.close()}>
          Done
        </button>
      </div>
      <p className="px-5 pt-1 pb-3 type-callout text-secondary">
        Auden wasn’t sure about these. Accepting links the file to the YouTube Music song, so it shows “On This Device” when you stream it.
      </p>
      <ul className="max-h-[60vh] overflow-y-auto px-2 pb-3">
        {rows.length === 0 && <li className="px-3 py-6 text-center type-callout text-secondary">Nothing left to review.</li>}
        {rows.map((row) => (
          <li key={row.id} className="flex items-center gap-3 rounded-xl px-3 py-2.5 hover:bg-quinary">
            <div className="min-w-0 flex-1">
              <div className="truncate type-body-emphasized">{row.title}</div>
              <div className="truncate type-callout text-secondary">{row.artist}</div>
              <div className="truncate type-callout text-tertiary">
                → {row.suggestedTitle} · {row.suggestedArtist}
              </div>
            </div>
            <button className="type-callout-emphasized text-key" onClick={() => void acceptSuggestion(row.id!)}>
              Accept
            </button>
            <button className="type-callout text-secondary" onClick={() => void rejectSuggestion(row.id!)}>
              Not this
            </button>
          </li>
        ))}
      </ul>
    </dialog>
  );
}
