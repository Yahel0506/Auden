import { number, t as translateUi } from '@/i18n';
import { useJobs } from '../../jobs/jobs';
import type { FolderOutcome } from './state';

const songCount = (count: number) => translateUi('folder_report.song_count', { count });

/** The sentence for the job banner after a "Save to Folder". */
export function describeOutcome(outcome: FolderOutcome): string {
  if (outcome.kind === 'no-folder') return translateUi('folder_report.choose_a_folder_in_settings');
  if (outcome.kind === 'no-access') return translateUi('folder_report.browser_needs_folder_permission');
  const parts: string[] = [];
  if (outcome.saved) parts.push(translateUi('folder_report.songs_saved', { songs: songCount(outcome.saved) }));
  if (outcome.already) parts.push(translateUi('folder_report.songs_already_there', { count: number(outcome.already) }));
  if (outcome.queued) parts.push(translateUi('folder_report.songs_queued', { songs: songCount(outcome.queued) }));
  if (outcome.failed.length) parts.push(translateUi('folder_report.songs_failed', { count: number(outcome.failed.length) }));
  let text = parts.join(', ') || translateUi('folder_report.nothing_to_save');
  if (outcome.stopped === 'permission') text += ` ${translateUi('folder_report.permission_was_revoked')}`;
  else if (outcome.stopped === 'folder-missing') text += ` ${translateUi('folder_report.folder_missing')}`;
  else if (outcome.stopped === 'disk-full') text += ` ${translateUi('folder_report.disk_full')}`;
  return text;
}

/** Shows [outcome] as a one-line job in the banner under the player. */
export function announce(outcome: FolderOutcome): void {
  const id = 'offline-folder';
  const jobs = useJobs.getState();
  jobs.start({ id, title: translateUi('folder_report.save_to_folder'), total: 1 });
  jobs.finish(id, describeOutcome(outcome));
}
