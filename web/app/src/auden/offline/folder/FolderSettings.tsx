import { useState } from 'react';
import { settingsUi } from '@/pages/settingsStyles';
import { folderSupported } from './access';
import { useFolderState } from './hooks';
import { DEFAULT_TEMPLATE, PRESETS, preview, validTemplate } from './layout';
import { allowAccess, chooseFolder, forgetFolder, setTemplate } from './state';

const button =
  'rounded-[8px] bg-quaternary px-3 py-1.5 type-callout-emphasized text-primary enabled:hover:bg-tertiary disabled:opacity-40';

const ACCESS_TEXT = { granted: 'Connected', prompt: 'Needs your permission', denied: 'Permission refused', missing: 'Folder not found' } as const;

/** Settings › Offline copies › Folder (Chromium only): where "Save to Folder" writes, and how the files are named. */
export function FolderSettings() {
  const { record, access, overlap } = useFolderState();
  const [draft, setDraft] = useState<string | null>(null);
  if (!folderSupported()) {
    return <p className={settingsUi.footer}>Saving into a folder of your own needs Chrome, Edge or another Chromium browser.</p>;
  }
  const template = draft ?? record?.template ?? DEFAULT_TEMPLATE;
  const valid = validTemplate(template);
  return (
    <>
      <div className={settingsUi.controlRow}>
        <span className={settingsUi.rowText}>
          <span>Folder for saved songs</span>
          <span className={settingsUi.rowDetail}>{record ? `${record.name} · ${ACCESS_TEXT[access]}` : 'None chosen'}</span>
        </span>
        <span className="flex gap-2">
          {record && access !== 'granted' && access !== 'missing' && (
            <button className={button} onClick={() => void allowAccess()}>
              Allow access
            </button>
          )}
          <button className={button} onClick={() => void chooseFolder()}>
            {record ? 'Change…' : 'Choose folder…'}
          </button>
          {record && (
            <button className={button} onClick={() => void forgetFolder()}>
              Forget
            </button>
          )}
        </span>
      </div>
      {record && (
        <div className={settingsUi.controlRow}>
          <span className={settingsUi.rowText}>
            <span>File names</span>
            <span className={settingsUi.rowDetail}>
              {valid ? `e.g. ${preview(template)}` : 'Use {title} (or {videoId}), and {artist} {albumArtist} {album} {track}; no “..”, leading or trailing “/”.'}
            </span>
            <select
              className="mt-1 w-full rounded-[8px] bg-quaternary px-2 py-1 type-callout"
              aria-label="Layout"
              value={PRESETS.some((p) => p.template === template) ? template : ''}
              onChange={(e) => {
                if (!e.target.value) return;
                setDraft(null);
                void setTemplate(e.target.value);
              }}
            >
              {PRESETS.map((p) => (
                <option key={p.template} value={p.template}>
                  {p.label}: {p.template}
                </option>
              ))}
              <option value="">Custom</option>
            </select>
            <input
              className="mt-1 w-full rounded-[8px] bg-quaternary px-2 py-1 type-callout"
              aria-label="Custom layout"
              aria-invalid={!valid}
              value={template}
              onChange={(e) => {
                setDraft(e.target.value);
                if (validTemplate(e.target.value)) void setTemplate(e.target.value);
              }}
            />
          </span>
        </div>
      )}
      {overlap && (
        <p className={settingsUi.footer} role="status">
          “{overlap}” is one of your music folders and overlaps this one: saved songs would also show up in On This Device.
        </p>
      )}
      <p className={settingsUi.footer}>
        Songs are saved as YouTube serves them (Opus or AAC), without embedded tags: this browser can’t write them. The name carries the
        artist and title, a <code>.lrc</code> with synced lyrics goes beside it, and a <code>cover</code> image (YouTube’s thumbnail) once
        per album. Nothing in your folder is ever replaced. The copy kept inside the browser stays too, so the space is used twice. After
        you reopen the browser, saving waits until you tap Allow access.
      </p>
    </>
  );
}
