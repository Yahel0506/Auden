import { useState } from 'react';
import { detailButton } from '@/components/music/DetailHeader';

/** "Save to Device" in a detail header: keeps a copy on this device that gains new songs by itself. */
export function SaveToDeviceButton({ onSave }: { onSave: () => Promise<unknown> }) {
  const [busy, setBusy] = useState(false);
  return (
    <button
      className={detailButton.pill}
      disabled={busy}
      title="Keeps a copy on this device. New songs from the original are added automatically; nothing changes on the original."
      onClick={() => {
        setBusy(true);
        void onSave().finally(() => setBusy(false));
      }}
    >
      {busy ? 'Saving…' : 'Save to Device'}
    </button>
  );
}
