import { t as translateUi } from '@/i18n';
import { useState } from 'react';
import { detailButton } from '@/components/music/DetailHeader';

/** "Save to Device" in a detail header: keeps a copy on this device that gains new songs by itself. */
export function SaveToDeviceButton({ onSave }: { onSave: () => Promise<unknown> }) {
  const [busy, setBusy] = useState(false);
  return (
    <button
      className={detailButton.pill}
      disabled={busy}
      title={translateUi("save_to_device_button.keeps_a_copy_on_this")}
      onClick={() => {
        setBusy(true);
        void onSave().finally(() => setBusy(false));
      }}
    >
      {busy ? translateUi("playlists_ui_savetodevicebutton_saving") : translateUi("playlists_ui_savetodevicebutton_save_to_device")}
    </button>
  );
}
