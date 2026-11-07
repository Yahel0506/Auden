import { useEffect, useMemo, useState } from 'react';
import { PageHeader } from '@/components/PageHeader';
import { EmptyState } from '@/components/music/States';
import type { Track } from '@/data/music/types';
import { playList } from '@/components/music/play';
import { auditOffline, formatBytes, repairOffline, usageBy, type AuditReport } from '@/auden/offline/audit';
import { removeOfflineCopies } from '@/auden/offline/actions';
import { useOfflineItems, useOfflineQueue } from '@/auden/offline/hooks';
import { folderSupported } from '@/auden/offline/folder/access';
import { useFolderState, usePendingCount } from '@/auden/offline/folder/hooks';
import { announce } from '@/auden/offline/folder/report';
import { allowAccess, saveKeptToFolder } from '@/auden/offline/folder/state';
import { FollowedList, KeepUpdatedPrompts } from '@/auden/offline/ui/KeepUpdatedPrompts';
import { getOfflineRuntime, keepStorage } from '@/auden/offline/runtime';
import { PAUSE_TEXT, type OfflineItem } from '@/auden/offline/types';

const button = 'type-callout-emphasized text-key hover:underline disabled:opacity-50';
const card = 'mb-3 rounded-[12px] bg-quinary px-4 py-3 type-callout';
const row = 'flex items-center gap-3 px-4 py-2.5';

const trackOf = (item: OfflineItem): Track => ({
  videoId: item.videoId,
  title: item.title,
  artists: item.artist ? [{ name: item.artist, id: item.artistId }] : [],
  album: item.album ? { name: item.album, id: item.albumId } : null,
  durationSec: item.durationSec,
  explicit: false,
  thumbnail: item.thumbnailUrl,
  index: null,
  plays: null,
});

function useCoverUrl(blob: Blob | null): string | null {
  const [url, setUrl] = useState<string | null>(null);
  useEffect(() => {
    if (!blob) return setUrl(null);
    const next = URL.createObjectURL(blob);
    setUrl(next);
    return () => URL.revokeObjectURL(next);
  }, [blob]);
  return url;
}

function Cover({ blob, fallback }: { blob: Blob | null; fallback: string | null }) {
  const local = useCoverUrl(blob);
  const src = local ?? fallback;
  return src ? <img src={src} alt="" className="size-10 rounded-[6px] bg-quinary object-cover" /> : <div className="size-10 rounded-[6px] bg-quinary" />;
}

function Storage() {
  const [text, setText] = useState('');
  const [persisted, setPersisted] = useState<boolean | null>(null);
  const items = useOfflineItems();
  useEffect(() => {
    void (async () => {
      const est = await navigator.storage?.estimate?.().catch(() => null);
      setPersisted((await navigator.storage?.persisted?.().catch(() => false)) ?? false);
      if (est?.quota) setText(`${formatBytes(est.usage ?? 0)} of ${formatBytes(est.quota)} used by this site`);
    })();
  }, [items]);
  const kept = (items ?? []).reduce((n, i) => n + i.bytes, 0);
  return (
    <div className={card}>
      <p className="type-callout-emphasized">{formatBytes(kept)} in {items?.length ?? 0} offline {items?.length === 1 ? 'song' : 'songs'}</p>
      {text && <p className="text-secondary">{text}</p>}
      <p className="text-secondary">
        {persisted ? 'The browser will keep these copies.' : 'The browser may clear these copies if the device runs low on space.'}{' '}
        {!persisted && (
          <button className={button} onClick={() => void keepStorage().then(setPersisted)}>
            Ask it to keep them
          </button>
        )}
      </p>
    </div>
  );
}

/** The folder the songs are saved into (Chromium), what is waiting for it, and the one-tap "save everything". */
function FolderCard() {
  const { record, access } = useFolderState();
  const pending = usePendingCount();
  const items = useOfflineItems();
  const [busy, setBusy] = useState(false);
  if (!folderSupported() || !record) return null;
  const notThere = (items ?? []).filter((i) => !i.folder).length;
  return (
    <div className={card}>
      <p className="type-callout-emphasized">Folder: {record.name}</p>
      <p className="text-secondary">
        {access === 'granted'
          ? `${(items ?? []).filter((i) => i.folder).length} songs saved there.`
          : access === 'missing'
            ? 'The folder was moved or deleted. Choose it again in Settings.'
            : 'The browser needs your permission to write to it.'}
        {pending > 0 && ` ${pending} waiting for the folder.`}
      </p>
      <p className="mt-1 flex gap-4">
        {access !== 'granted' && access !== 'missing' && (
          <button className={button} onClick={() => void allowAccess()}>
            Allow access
          </button>
        )}
        {access === 'granted' && notThere > 0 && (
          <button
            className={button}
            disabled={busy}
            onClick={() => {
              setBusy(true);
              void saveKeptToFolder().then(announce).finally(() => setBusy(false));
            }}
          >
            Save {notThere} to folder
          </button>
        )}
      </p>
    </div>
  );
}

function QueueSection() {
  const { rows, paused } = useOfflineQueue();
  const { queue } = getOfflineRuntime();
  if (!rows.length && !paused) return null;
  const failed = rows.filter((r) => r.status === 'failed');
  return (
    <section aria-label="Downloading">
      <h2 className="mb-2 type-subhead-emphasized uppercase text-secondary">Downloading</h2>
      {paused && (
        <div className={card + ' flex items-center justify-between gap-3'}>
          <span>{PAUSE_TEXT[paused.reason]}</span>
          <button className={button} onClick={() => void queue.resume()}>
            Resume
          </button>
        </div>
      )}
      <div className="mb-2 flex gap-4">
        {!paused && rows.some((r) => r.status !== 'failed') && (
          <button className={button} onClick={() => void queue.pause()}>
            Pause all
          </button>
        )}
        {failed.length > 0 && (
          <>
            <button className={button} onClick={() => void queue.retry()}>
              Retry {failed.length} failed
            </button>
            <button className={button} onClick={() => void queue.clearFailed()}>
              Clear failed
            </button>
          </>
        )}
      </div>
      <ul className="mb-8 overflow-hidden rounded-[10px] bg-quinary">
        {rows.map(({ job, status, done, total }) => (
          <li key={job.videoId} className={row} data-status={status}>
            <div className="min-w-0 grow">
              <p className="truncate type-body-reduced">{job.title}</p>
              <p className="truncate type-callout text-secondary">
                {job.artist} ·{' '}
                {status === 'active'
                  ? total
                    ? `${Math.round((done / total) * 100)}%`
                    : 'Starting…'
                  : status === 'failed'
                    ? (job.reason ?? 'Failed')
                    : status === 'waiting'
                      ? `Will try again (${job.reason ?? 'error'})`
                      : 'Waiting'}
              </p>
              {status === 'active' && total > 0 && <progress className="mt-1 h-1 w-full" value={done} max={total} />}
            </div>
            {status === 'failed' && (
              <button className={button} onClick={() => void queue.retry([job.videoId])}>
                Retry
              </button>
            )}
            <button className={button} onClick={() => void queue.cancel(job.videoId)}>
              {status === 'failed' ? 'Remove' : 'Cancel'}
            </button>
          </li>
        ))}
      </ul>
    </section>
  );
}

function CopiesSection() {
  const items = useOfflineItems();
  const [by, setBy] = useState<'album' | 'artist'>('album');
  const groups = useMemo(() => usageBy(items ?? [], by), [items, by]);
  if (!items) return null;
  return (
    <section aria-label="Offline songs">
      <div className="mb-2 flex items-center justify-between">
        <h2 className="type-subhead-emphasized uppercase text-secondary">Offline songs</h2>
        <div className="flex gap-3">
          {(['album', 'artist'] as const).map((k) => (
            <button key={k} className={`${button} ${by === k ? 'underline' : ''}`} onClick={() => setBy(k)}>
              By {k}
            </button>
          ))}
        </div>
      </div>
      {!items.length && <EmptyState title="Nothing saved yet" detail="Use “Save for Offline” on a song, album or playlist." />}
      {groups.map((group) => (
        <details key={group.key} className="mb-2 overflow-hidden rounded-[10px] bg-quinary">
          <summary className={`${row} cursor-pointer`}>
            <Cover blob={group.items[0]?.cover ?? null} fallback={group.items[0]?.thumbnailUrl ?? null} />
            <span className="min-w-0 grow truncate type-body-reduced">{group.name}</span>
            <span className="type-callout text-secondary">
              {group.songs} · {formatBytes(group.bytes)}
            </span>
          </summary>
          <div className="px-4 pb-2">
            <div className="flex gap-4 py-1">
              <button className={button} onClick={() => playList(group.items.map(trackOf), 0, { source: { title: group.name, type: 'album', id: group.key }, fallbackArtwork: null, fallbackArtists: [] })}>
                Play
              </button>
              <button className={button} onClick={() => void removeOfflineCopies(group.items.map((i) => i.videoId))}>
                Remove all
              </button>
            </div>
            <ul>
              {group.items.map((item) => (
                <li key={item.videoId} className="flex items-center gap-3 py-1.5">
                  <span className="min-w-0 grow truncate type-body-reduced">{item.title}</span>
                  <span className="type-callout text-secondary">{formatBytes(item.bytes)}</span>
                  <button className={button} onClick={() => void removeOfflineCopies([item.videoId])}>
                    Remove
                  </button>
                </li>
              ))}
            </ul>
          </div>
        </details>
      ))}
    </section>
  );
}

function AuditSection() {
  const [report, setReport] = useState<AuditReport | null>(null);
  const [fixed, setFixed] = useState<string | null>(null);
  const rt = getOfflineRuntime();
  const { rows } = useOfflineQueue();
  const check = async () => {
    setFixed(null);
    setReport(await auditOffline(rt.store, rt.files, new Set(rows.map((r) => r.job.videoId))));
  };
  const problems = report ? report.orphans.length + report.broken.length : 0;
  return (
    <section className="mt-8" aria-label="Check files">
      <h2 className="mb-2 type-subhead-emphasized uppercase text-secondary">Check files</h2>
      <div className={card}>
        <button className={button} onClick={() => void check()}>
          Check offline files
        </button>
        {report && (
          <p className="mt-2 text-secondary" role="status">
            {problems
              ? `${report.orphans.length} stray ${report.orphans.length === 1 ? 'file' : 'files'} (${formatBytes(report.orphanBytes)}), ${report.broken.length} ${report.broken.length === 1 ? 'song' : 'songs'} whose file is missing or damaged.`
              : `All good: ${formatBytes(report.keptBytes)} match their records.`}{' '}
            {problems > 0 && (
              <button
                className={button}
                onClick={() =>
                  void repairOffline(report, rt.store, rt.files).then(({ files, records }) => {
                    rt.bus.emit();
                    setReport(null);
                    setFixed(`Removed ${files} files and ${records} records.`);
                  })
                }
              >
                Clean up
              </button>
            )}
          </p>
        )}
        {fixed && <p className="mt-2 text-secondary">{fixed}</p>}
      </div>
    </section>
  );
}

export default function OfflinePage() {
  const supported = getOfflineRuntime().supported;
  return (
    <div>
      <PageHeader title="Offline" />
      <div className="mx-(--body-gutter) mt-5 mb-[120px] max-w-[720px]">
        {!supported ? (
          <EmptyState title="Not available in this browser" detail="Offline copies need the browser’s private file storage (OPFS) with writable files." />
        ) : (
          <>
            <Storage />
            <KeepUpdatedPrompts />
            <FollowedList />
            <FolderCard />
            <QueueSection />
            <CopiesSection />
            <AuditSection />
          </>
        )}
      </div>
    </div>
  );
}
