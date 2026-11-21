import { t as translateUi } from '@/i18n';
import { useState } from 'react';
import { settingsUi } from '@/pages/settingsStyles';
import { folderSupported } from './access';
import { useFolderState } from './hooks';
import { DEFAULT_TEMPLATE, PRESETS, preview, validTemplate } from './layout';
import { allowAccess, chooseFolder, forgetFolder, setTemplate } from './state';

const button =
  'rounded-[8px] bg-quaternary px-3 py-1.5 type-callout-emphasized text-primary enabled:hover:bg-tertiary disabled:opacity-40';

const ACCESS_TEXT = {
  granted: 'offline_folder.connected',
  prompt: 'offline_folder.needs_your_permission',
  denied: 'offline_folder.permission_refused',
  missing: 'offline_folder.folder_not_found',
} as const;

/** Settings › Offline copies › Folder (Chromium only): where "Save to Folder" writes, and how the files are named. */
export function FolderSettings() {
  const { record, access, overlap } = useFolderState();
  const [draft, setDraft] = useState<string | null>(null);
  if (!folderSupported()) {
    return <p className={settingsUi.footer}>{translateUi("folder_settings.saving_into_a_folder_of")}</p>;
  }
  const template = draft ?? record?.template ?? DEFAULT_TEMPLATE;
  const valid = validTemplate(template);
  return (
    <>
      <div className={settingsUi.controlRow}>
        <span className={settingsUi.rowText}>
          <span>{translateUi("folder_settings.folder_for_saved_songs")}</span>
          <span className={settingsUi.rowDetail}>{record ? translateUi('offline_folder.folder_access_status', { folder: record.name, status: translateUi(ACCESS_TEXT[access]) }) : translateUi("offline_folder_foldersettings_none_chosen")}</span>
        </span>
        <span className="flex gap-2">
          {record && access !== 'granted' && access !== 'missing' && (
            <button className={button} onClick={() => void allowAccess()}>{translateUi("folder_settings.allow_access")}</button>
          )}
          <button className={button} onClick={() => void chooseFolder()}>
            {record ? translateUi("offline_folder_foldersettings_change") : translateUi("offline_folder_foldersettings_choose_folder")}
          </button>
          {record && (
            <button className={button} onClick={() => void forgetFolder()}>{translateUi("folder_settings.forget")}</button>
          )}
        </span>
      </div>
      {record && (
        <div className={settingsUi.controlRow}>
          <span className={settingsUi.rowText}>
            <span>{translateUi("folder_settings.file_names")}</span>
            <span className={settingsUi.rowDetail}>
              {valid ? translateUi('offline_folder.example_template', { template: preview(template) }) : translateUi("offline_folder_foldersettings_use_title_or_videoid_and")}
            </span>
            <select
              className="mt-1 w-full rounded-[8px] bg-quaternary px-2 py-1 type-callout"
              aria-label={translateUi("folder_settings.layout")}
              value={PRESETS.some((p) => p.template === template) ? template : ''}
              onChange={(e) => {
                if (!e.target.value) return;
                setDraft(null);
                void setTemplate(e.target.value);
              }}
            >
              {PRESETS.map((p) => (
                <option key={p.template} value={p.template}>
                  {translateUi(p.label as keyof typeof import('@/i18n.en.json'))}: {p.template}
                </option>
              ))}
              <option value="">{translateUi("folder_settings.custom")}</option>
            </select>
            <input
              className="mt-1 w-full rounded-[8px] bg-quaternary px-2 py-1 type-callout"
              aria-label={translateUi("folder_settings.custom_layout")}
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
          {translateUi('offline_folder.overlaps_music_folder', { folder: overlap })}
        </p>
      )}
      <p className={settingsUi.footer}>
        {translateUi('offline_folder.storage_explanation', {
          lrc: translateUi('folder_settings.lrc'),
          cover: translateUi('folder_settings.cover'),
        })}
      </p>
    </>
  );
}
