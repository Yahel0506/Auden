import { t as translateUi, date as formatDate, number as formatNumber } from '@/i18n';
import { useRef, useState } from 'react';
import { downloadText } from '@/auden/lyrics/actions';
import { settingsUi } from '@/pages/settingsStyles';
import { exportBackup, type ExportReport } from '../export';
import { BackupError, MAX_BACKUP_BYTES, parseBackup, type BackupFile } from '../format';
import { importBackup, isRestoring, previewImport, type ImportPreview, type ImportSummary } from '../import';
import { readHeldSettings } from '../passthrough';

const button =
  'rounded-[8px] bg-quaternary px-3 py-1.5 type-callout-emphasized text-primary enabled:hover:bg-tertiary disabled:opacity-40';
const note = 'px-4 pb-3 type-callout text-secondary';

const count = (n: number, key: 'songs' | 'playlists' | 'months' | 'lyrics' | 'entries' | 'folders' | 'settings' | 'items') =>
  translateUi(`backup_section.count_${key}` as keyof typeof import('@/i18n.en.json'), { count: n });
const when = (iso: string) => (iso ? formatDate(new Date(iso)) : translateUi('backup_section.unknown_date'));
const mb = (bytes: number) => `${(bytes / 1024 / 1024).toFixed(1)} MB`;
function backupErrorText(error: BackupError): string {
  if (error.message.includes('newer version')) return translateUi('backup_section.newer_version');
  if (error.message.includes('another app')) return translateUi('backup_section.from_another_app');
  if (error.message.includes('saved playlists are being updated')) return translateUi('backup_section.playlists_updating');
  if (error.message.includes('already running')) return translateUi('backup_section.restore_already_running');
  return translateUi('backup_section.invalid_file');
}

/** The listener's own copy of what they have here, and the way back from one (Backup.kt on the phone). */
export function BackupSection() {
  const held = readHeldSettings();
  const [ack, setAck] = useState(false);
  const [exporting, setExporting] = useState(false);
  const [exported, setExported] = useState<ExportReport | null>(null);
  const [problem, setProblem] = useState<string | null>(null);

  const [reading, setReading] = useState(false);
  const [pending, setPending] = useState<{ file: BackupFile; preview: ImportPreview; name: string } | null>(null);
  const [restoring, setRestoring] = useState(false);
  const [restored, setRestored] = useState<ImportSummary | null>(null);
  const picker = useRef<HTMLInputElement>(null);

  const save = async () => {
    setExporting(true);
    setProblem(null);
    try {
      const { text, name, report } = await exportBackup();
      downloadText(name, text);
      setExported(report);
    } catch (error) {
      console.error('Auden backup export failed', error);
      setProblem(translateUi('backup_section.export_failed'));
    } finally {
      setExporting(false);
    }
  };

  const choose = async (file: File | undefined) => {
    if (!file) return;
    setProblem(null);
    setRestored(null);
    setPending(null);
    if (file.size > MAX_BACKUP_BYTES) {
      setProblem(translateUi('backup_section.file_too_large', { size: mb(file.size), limit: mb(MAX_BACKUP_BYTES) }));
      return;
    }
    setReading(true);
    try {
      const parsed = parseBackup(await file.text());
      setPending({ file: parsed, preview: await previewImport(parsed), name: file.name });
    } catch (error) {
      if (error instanceof BackupError) {
        console.warn('Auden backup was rejected', error.message);
        setProblem(backupErrorText(error));
      }
      else { console.error('Auden backup read failed', error); setProblem(translateUi('backup_section.read_failed')); }
    } finally {
      setReading(false);
      if (picker.current) picker.current.value = '';
    }
  };

  const restore = async () => {
    if (!pending) return;
    setRestoring(true);
    setProblem(null);
    try {
      setRestored(await importBackup(pending.file));
      setPending(null);
    } catch (error) {
      // Nothing was changed: the restore is one transaction.
      console.error('Auden backup restore failed', error);
      setProblem(translateUi('backup_section.restore_failed_nothing_changed'));
    } finally {
      setRestoring(false);
    }
  };

  const preview = pending?.preview;
  return (
    <>
      <h2 className={settingsUi.sectionHeader}>{translateUi("backup_section.backup")}</h2>
      <section className={settingsUi.group}>
        <div className={settingsUi.row}>
          <span className={settingsUi.rowText}>
            <span>{translateUi("backup_section.export_a_backup")}</span>
            <span className={settingsUi.rowDetail}>{translateUi("backup_section.songs_playlists_saved_lyrics_spotify")}</span>
          </span>
          <button className={button} disabled={exporting || (!held && !ack)} onClick={() => void save()}>
            {exporting ? translateUi("backup_ui_backupsection_exporting") : translateUi('backup_section.export')}
          </button>
        </div>
        {held ? (
          <p className={note}>
            {translateUi('backup_section.held_phone_settings', { date: when(held.capturedAt) })}
          </p>
        ) : (
          <label className={`${note} flex cursor-pointer items-start gap-2`}>
            <input type="checkbox" className="mt-1" checked={ack} onChange={(e) => setAck(e.currentTarget.checked)} />
            <span>{translateUi("backup_section.this_browser_has_no_settings")}</span>
          </label>
        )}
        {exported && (
          <p className={note} role="status">
            {translateUi('backup_section.exported_summary', { songs: count(exported.songs, 'songs'), playlists: count(exported.playlists, 'playlists'), months: count(exported.months, 'months') })}
            {exported.webFilesLeftOut > 0 &&
              ` ${translateUi('backup_section.web_files_left_out', { songs: count(exported.webFilesLeftOut, 'songs'), entries: count(exported.entriesLeftOut, 'entries') })}`}
          </p>
        )}

        <div className={`${settingsUi.row} border-t-[0.5px] border-t-divider`}>
          <span className={settingsUi.rowText}>
            <span>{translateUi("backup_section.restore_a_backup")}</span>
            <span className={settingsUi.rowDetail}>{translateUi("backup_section.from_this_app_the_android")}</span>
          </span>
          <button className={button} disabled={reading || restoring} onClick={() => picker.current?.click()}>
            {reading ? translateUi("backup_ui_backupsection_reading") : translateUi("backup_ui_backupsection_choose_file")}
          </button>
          <input ref={picker} type="file" accept="application/json,.json" className="hidden" onChange={(e) => void choose(e.currentTarget.files?.[0])} />
        </div>

        {pending && preview && (
          <div className={`${note} border-t-[0.5px] border-t-divider pt-3`} role="alertdialog" aria-label={translateUi("backup_section.confirm_restore")}>
            <p className="type-body-emphasized text-primary">
              {translateUi('backup_section.backup_file_heading', { kind: preview.bitchord ? 'BitChord' : 'Auden', name: pending.name, date: when(preview.exportedAt) })}
              {preview.versionName ? ` (${preview.versionName})` : ''}
            </p>
            {preview.replaces === 'library' ? (
              <>
                <p className="mt-1">
                  {translateUi('backup_section.replace_library_summary', {
                    currentSongs: count(preview.current.songs, 'songs'), currentPlaylists: count(preview.current.playlists, 'playlists'), currentLyrics: count(preview.current.lyrics, 'lyrics'), currentMonths: count(preview.current.months, 'months'),
                    newSongs: count(preview.willWrite.songs, 'songs'), newPlaylists: count(preview.willWrite.playlists, 'playlists'), newLyrics: count(preview.willWrite.lyrics, 'lyrics'), newMonths: count(preview.willWrite.months, 'months'),
                  })}
                </p>
                {preview.kept.webFiles > 0 && (
                  <p className="mt-1">
                    {translateUi('backup_section.kept_local_files', { songs: count(preview.kept.webFiles, 'songs'), relinked: preview.kept.relinked ? ` (${formatNumber(preview.kept.relinked)} ${translateUi('backup_section.relinked_from_file')})` : '', folders: count(preview.kept.folders, 'folders') })}
                  </p>
                )}
                <p className="mt-1">{translateUi("backup_section.all_of_your_listening_here")}</p>
              </>
            ) : (
              <p className="mt-1">
                {translateUi('backup_section.replace_history_summary', { kind: preview.bitchord ? 'BitChord' : 'Auden', currentMonths: count(preview.current.months, 'months'), newMonths: count(preview.willWrite.months, 'months') })}
              </p>
            )}
            {(preview.entriesDropped > 0 || preview.songsDropped > 0 || preview.androidFiles > 0) && (
              <p className="mt-1">
                {[
                  preview.entriesDropped > 0 && translateUi('backup_section.entries_dropped', { entries: count(preview.entriesDropped, 'entries') }),
                  preview.songsDropped > 0 && translateUi('backup_section.songs_dropped', { songs: count(preview.songsDropped, 'songs') }),
                  preview.androidFiles > 0 && translateUi('backup_section.android_files', { songs: count(preview.androidFiles, 'songs') }),
                ]
                  .filter(Boolean)
                  .join('; ')}
                .
              </p>
            )}
            {preview.settings > 0 && <p className="mt-1">{translateUi('backup_section.phone_settings_kept', { settings: count(preview.settings, 'settings') })}</p>}
            <div className="mt-3 flex flex-wrap gap-2">
              {/* A copy of what is here now, before it is replaced. */}
              {/* Under the same acknowledgement as Export: this file looks like any other and would reset the phone's settings. */}
              <button className={button} disabled={restoring || exporting || (!held && !ack)} onClick={() => void save()}>{translateUi("backup_section.export_a_copy_first")}</button>
              <button className={button} disabled={restoring} onClick={() => setPending(null)}>{translateUi("backup_section.cancel")}</button>
              <button className={`${button} text-red`} disabled={restoring || isRestoring()} onClick={() => void restore()}>
                {restoring ? translateUi("backup_ui_backupsection_restoring") : translateUi("backup_ui_backupsection_replace_and_restore")}
              </button>
            </div>
          </div>
        )}

        {restored && (
          <div className={note} role="status">
            <p className="type-body-emphasized text-primary">{translateUi("backup_section.restored")}</p>
            <p className="mt-1">
              {restored.replacedLibrary
                ? translateUi('backup_section.restored_library_summary', { songs: count(restored.songs, 'songs'), playlists: count(restored.playlists, 'playlists'), lyrics: count(restored.lyrics, 'lyrics'), months: count(restored.months, 'months') })
                : translateUi('backup_section.restored_history_summary', { months: count(restored.months, 'months') })}
              {restored.songsDropped + restored.entriesDropped > 0 && ` ${translateUi('backup_section.restored_items_dropped', { items: count(restored.songsDropped + restored.entriesDropped, 'items') })}`}
            </p>
            <p className="mt-1">{translateUi("backup_section.reload_to_see_everything_as")}</p>
            <button className={`${button} mt-2`} onClick={() => window.location.reload()}>{translateUi("backup_section.reload")}</button>
          </div>
        )}
        {problem && (
          <p className={`${note} text-red`} role="alert">
            {problem}
          </p>
        )}
      </section>
      <p className={settingsUi.footer}>{translateUi("backup_section.a_restore_replaces_it_never")}</p>
    </>
  );
}
