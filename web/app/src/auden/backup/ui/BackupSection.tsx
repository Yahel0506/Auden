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

const plural = (n: number, one: string, many = `${one}s`) => `${n.toLocaleString()} ${n === 1 ? one : many}`;
const when = (iso: string) => (iso ? new Date(iso).toLocaleDateString() : 'an unknown date');
const mb = (bytes: number) => `${(bytes / 1024 / 1024).toFixed(1)} MB`;

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
      setProblem(error instanceof Error ? error.message : 'Couldn’t make the backup.');
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
      setProblem(`That file is ${mb(file.size)}, more than the ${mb(MAX_BACKUP_BYTES)} a backup can be here.`);
      return;
    }
    setReading(true);
    try {
      const parsed = parseBackup(await file.text());
      setPending({ file: parsed, preview: await previewImport(parsed), name: file.name });
    } catch (error) {
      setProblem(error instanceof BackupError ? error.message : 'Couldn’t read that file.');
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
      setProblem(`${error instanceof Error ? error.message : 'The restore failed.'} Nothing was changed.`);
    } finally {
      setRestoring(false);
    }
  };

  const preview = pending?.preview;
  return (
    <>
      <h2 className={settingsUi.sectionHeader}>Backup</h2>
      <section className={settingsUi.group}>
        <div className={settingsUi.row}>
          <span className={settingsUi.rowText}>
            <span>Export a backup</span>
            <span className={settingsUi.rowDetail}>Songs, playlists, saved lyrics, Spotify matches and listening, in the file the Android app reads.</span>
          </span>
          <button className={button} disabled={exporting || (!held && !ack)} onClick={() => void save()}>
            {exporting ? 'Exporting…' : 'Export'}
          </button>
        </div>
        {held ? (
          <p className={note}>
            Your phone’s settings, from the backup of {when(held.capturedAt)}, go back in unchanged. Restoring this file on the phone returns its settings to
            that date. They include its search history, which is kept in this browser for that.
          </p>
        ) : (
          <label className={`${note} flex cursor-pointer items-start gap-2`}>
            <input type="checkbox" className="mt-1" checked={ack} onChange={(e) => setAck(e.currentTarget.checked)} />
            <span>
              This browser has no settings from your phone to send back. Restoring this file on the phone would reset the phone’s settings and replace its
              listening history with this browser’s. I understand.
            </span>
          </label>
        )}
        {exported && (
          <p className={note} role="status">
            Exported {plural(exported.songs, 'song')}, {plural(exported.playlists, 'playlist')} and {plural(exported.months, 'month')} of listening.
            {exported.webFilesLeftOut > 0 &&
              ` ${plural(exported.webFilesLeftOut, 'song')} that exist only as files on this computer stay behind (the phone would delete them), along with their place in ${plural(exported.entriesLeftOut, 'playlist entry', 'playlist entries')}.`}
          </p>
        )}

        <div className={`${settingsUi.row} border-t-[0.5px] border-t-divider`}>
          <span className={settingsUi.rowText}>
            <span>Restore a backup</span>
            <span className={settingsUi.rowDetail}>From this app, the Android app or BitChord. You are asked before anything changes.</span>
          </span>
          <button className={button} disabled={reading || restoring} onClick={() => picker.current?.click()}>
            {reading ? 'Reading…' : 'Choose File…'}
          </button>
          <input ref={picker} type="file" accept="application/json,.json" className="hidden" onChange={(e) => void choose(e.currentTarget.files?.[0])} />
        </div>

        {pending && preview && (
          <div className={`${note} border-t-[0.5px] border-t-divider pt-3`} role="alertdialog" aria-label="Confirm restore">
            <p className="type-body-emphasized text-primary">
              {preview.bitchord ? 'BitChord backup' : 'Auden backup'} {pending.name}, from {when(preview.exportedAt)}
              {preview.versionName ? ` (${preview.versionName})` : ''}
            </p>
            {preview.replaces === 'library' ? (
              <>
                <p className="mt-1">
                  This replaces the library in this browser: its {plural(preview.current.songs, 'song')}, {plural(preview.current.playlists, 'playlist')},{' '}
                  {plural(preview.current.lyrics, 'saved lyric')} and {plural(preview.current.months, 'month')} of listening, with the file’s{' '}
                  {plural(preview.willWrite.songs, 'song')}, {plural(preview.willWrite.playlists, 'playlist')}, {plural(preview.willWrite.lyrics, 'saved lyric')}{' '}
                  and {plural(preview.willWrite.months, 'month')} of listening.
                </p>
                {preview.kept.webFiles > 0 && (
                  <p className="mt-1">
                    Kept: {plural(preview.kept.webFiles, 'song')} that are files in your music folders here
                    {preview.kept.relinked > 0 ? ` (${preview.kept.relinked.toLocaleString()} will take over details from the file)` : ''}, and {plural(preview.kept.folders, 'folder')}.
                    They leave the playlists they were in.
                  </p>
                )}
                <p className="mt-1">All of your listening here is replaced by the file’s; for songs that exist only as files on this computer it is simply gone, the backup format has no place for it.</p>
              </>
            ) : (
              <p className="mt-1">
                {preview.bitchord ? 'A BitChord backup' : 'This file has no library'}, so only your listening history is replaced: {plural(preview.current.months, 'month')} here with the
                file’s {plural(preview.willWrite.months, 'month')}. Your songs and playlists are not touched.
              </p>
            )}
            {(preview.entriesDropped > 0 || preview.songsDropped > 0 || preview.androidFiles > 0) && (
              <p className="mt-1">
                {[
                  preview.entriesDropped > 0 && `${plural(preview.entriesDropped, 'playlist entry', 'playlist entries')} can’t be restored (their song or playlist isn’t in the file)`,
                  preview.songsDropped > 0 && `${plural(preview.songsDropped, 'song')} left out (a file in another browser’s folder, or a duplicate)`,
                  preview.androidFiles > 0 && `${plural(preview.androidFiles, 'song')} are files on the phone: they keep their place in your playlists and go back on your next export; the ones with a YouTube version stream from it, the rest can’t be played here`,
                ]
                  .filter(Boolean)
                  .join('; ')}
                .
              </p>
            )}
            {preview.settings > 0 && <p className="mt-1">The file’s {plural(preview.settings, 'phone setting')} are kept to send back on your next export. Nothing in them is applied here.</p>}
            <div className="mt-3 flex flex-wrap gap-2">
              {/* A copy of what is here now, before it is replaced. */}
              {/* Under the same acknowledgement as Export: this file looks like any other and would reset the phone's settings. */}
              <button className={button} disabled={restoring || exporting || (!held && !ack)} onClick={() => void save()}>
                Export a copy first
              </button>
              <button className={button} disabled={restoring} onClick={() => setPending(null)}>
                Cancel
              </button>
              <button className={`${button} text-red`} disabled={restoring || isRestoring()} onClick={() => void restore()}>
                {restoring ? 'Restoring…' : 'Replace and Restore'}
              </button>
            </div>
          </div>
        )}

        {restored && (
          <div className={note} role="status">
            <p className="type-body-emphasized text-primary">Restored.</p>
            <p className="mt-1">
              {restored.replacedLibrary
                ? `${plural(restored.songs, 'song')}, ${plural(restored.playlists, 'playlist')}, ${plural(restored.lyrics, 'saved lyric')} and ${plural(restored.months, 'month')} of listening.`
                : `${plural(restored.months, 'month')} of listening.`}
              {restored.songsDropped + restored.entriesDropped > 0 && ` ${(restored.songsDropped + restored.entriesDropped).toLocaleString()} items couldn’t be restored.`}
            </p>
            <p className="mt-1">Reload to see everything as it is now: the queue and open pages still show what was here before.</p>
            <button className={`${button} mt-2`} onClick={() => window.location.reload()}>
              Reload
            </button>
          </div>
        )}
        {problem && (
          <p className={`${note} text-red`} role="alert">
            {problem}
          </p>
        )}
      </section>
      <p className={settingsUi.footer}>A restore replaces; it never merges. It happens all at once or not at all.</p>
    </>
  );
}
