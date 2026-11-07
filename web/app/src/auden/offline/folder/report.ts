import { useJobs } from '../../jobs/jobs';
import type { FolderOutcome } from './state';

const plural = (n: number, one: string, many = `${one}s`) => `${n} ${n === 1 ? one : many}`;

/** The sentence for the job banner after a "Save to Folder". */
export function describeOutcome(outcome: FolderOutcome): string {
  if (outcome.kind === 'no-folder') return 'Choose a folder in Settings › Offline copies first.';
  if (outcome.kind === 'no-access') return 'The browser needs your permission to write to the folder. Tap Allow access in Settings › Offline copies.';
  const parts: string[] = [];
  if (outcome.saved) parts.push(`${plural(outcome.saved, 'song')} saved`);
  if (outcome.already) parts.push(`${outcome.already} already there`);
  if (outcome.queued) parts.push(`${plural(outcome.queued, 'song')} will follow once downloaded`);
  if (outcome.failed.length) parts.push(`${outcome.failed.length} failed (${outcome.failed[0]!.reason})`);
  let text = parts.join(', ') || 'Nothing to save.';
  if (outcome.stopped === 'permission') text += ' Stopped: the browser took back the permission.';
  else if (outcome.stopped === 'folder-missing') text += ' Stopped: the folder is gone. Choose it again.';
  else if (outcome.stopped === 'disk-full') text += ' Stopped: no room left on the disk.';
  return text;
}

/** Shows [outcome] as a one-line job in the banner under the player. */
export function announce(outcome: FolderOutcome): void {
  const id = 'offline-folder';
  const jobs = useJobs.getState();
  jobs.start({ id, title: 'Save to folder', total: 1 });
  jobs.finish(id, describeOutcome(outcome));
}
