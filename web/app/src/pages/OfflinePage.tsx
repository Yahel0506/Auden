import { number, t as translateUi } from '@/i18n';
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
import type { OfflineItem, PauseReason } from '@/auden/offline/types';

const button = 'type-callout-emphasized text-key hover:underline disabled:opacity-50';
const card = 'mb-3 rounded-[12px] bg-quinary px-4 py-3 type-callout';
const row = 'flex items-center gap-3 px-4 py-2.5';

const pauseMessage: Record<PauseReason, Parameters<typeof translateUi>[0]> = {
  user: 'offline_page.paused',
  offline: 'offline_page.waiting_for_a_connection',
  'storage-full': 'offline_page.browser_storage_full',
  verify: 'offline_page.youtube_needs_verification',
};

function offlineFailure(reason: string | null): string {
  if (!reason) return translateUi('offline_page.download_failed');
  if (reason === translateUi('offline_queue.youtube_stream_only')) return translateUi('offline_page.youtube_only_streams_this_track');
  if (reason === translateUi('offline_queue.stream_limited_try_later')) return translateUi('offline_page.youtube_web_audio_unavailable');
  if (reason === translateUi('offline_queue.audio_no_longer_available')) return translateUi('offline_page.youtube_no_longer_has_audio');
  return translateUi('offline_page.download_failed');
}

function songCount(count: number): string {
  return translateUi('offline_page.song_count', { count });
}

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
      if (est?.quota) setText(translateUi('offline_page.storage_used_by_this_site', { used: formatBytes(est.usage ?? 0), quota: formatBytes(est.quota) }));
    })();
  }, [items]);
  const kept = (items ?? []).reduce((n, i) => n + i.bytes, 0);
  return (
    <div className={card}>
      <p className="type-callout-emphasized">{translateUi('offline_page.offline_storage_count', { size: formatBytes(kept), songs: songCount(items?.length ?? 0) })}</p>
      {text && <p className="text-secondary">{text}</p>}
      <p className="text-secondary">
        {persisted ? translateUi("pages_offlinepage_the_browser_will_keep_these") : translateUi("pages_offlinepage_the_browser_may_clear_these")}{' '}
        {!persisted && (
          <button className={button} onClick={() => void keepStorage().then(setPersisted)}>{translateUi("offline_page.ask_it_to_keep_them")}</button>
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
      <p className="type-callout-emphasized">{translateUi('offline_page.folder_name', { folder: record.name })}</p>
      <p className="text-secondary">
        {access === 'granted'
          ? translateUi('offline_page.songs_saved_in_folder', { songs: songCount((items ?? []).filter((i) => i.folder).length) })
          : access === 'missing'
            ? translateUi("pages_offlinepage_the_folder_was_moved_or")
            : translateUi("pages_offlinepage_the_browser_needs_your_permission")}
        {pending > 0 && ` ${translateUi('offline_page.songs_waiting_for_folder', { songs: songCount(pending) })}`}
      </p>
      <p className="mt-1 flex gap-4">
        {access !== 'granted' && access !== 'missing' && (
          <button className={button} onClick={() => void allowAccess()}>{translateUi("offline_page.allow_access")}</button>
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
            {translateUi('offline_page.save_songs_to_folder', { songs: number(notThere) })}
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
    <section aria-label={translateUi("offline_page.downloading")}>
      <h2 className="mb-2 type-subhead-emphasized uppercase text-secondary">{translateUi("offline_page.downloading")}</h2>
      {paused && (
        <div className={card + ' flex items-center justify-between gap-3'}>
          <span>{translateUi(pauseMessage[paused.reason])}</span>
          <button className={button} onClick={() => void queue.resume()}>{translateUi("offline_page.resume")}</button>
        </div>
      )}
      <div className="mb-2 flex gap-4">
        {!paused && rows.some((r) => r.status !== 'failed') && (
          <button className={button} onClick={() => void queue.pause()}>{translateUi("offline_page.pause_all")}</button>
        )}
        {failed.length > 0 && (
          <>
            <button className={button} onClick={() => void queue.retry()}>
              {translateUi('offline_page.retry_failed_songs', { count: number(failed.length) })}
            </button>
            <button className={button} onClick={() => void queue.clearFailed()}>{translateUi("offline_page.clear_failed")}</button>
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
                    ? translateUi('offline_page.progress_percent', { percent: Math.round((done / total) * 100) })
                    : translateUi('offline_page.starting')
                    : status === 'failed'
                    ? offlineFailure(job.reason)
                    : status === 'waiting'
                      ? translateUi('offline_page.will_try_again', { reason: offlineFailure(job.reason) })
                      : translateUi('offline_page.waiting')}
              </p>
              {status === 'active' && total > 0 && <progress className="mt-1 h-1 w-full" value={done} max={total} />}
            </div>
            {status === 'failed' && (
              <button className={button} onClick={() => void queue.retry([job.videoId])}>{translateUi("offline_page.retry")}</button>
            )}
            <button className={button} onClick={() => void queue.cancel(job.videoId)}>
              {status === 'failed' ? translateUi('offline_page.remove') : translateUi('offline_page.cancel')}
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
    <section aria-label={translateUi("offline_page.offline_songs")}>
      <div className="mb-2 flex items-center justify-between">
        <h2 className="type-subhead-emphasized uppercase text-secondary">{translateUi("offline_page.offline_songs")}</h2>
        <div className="flex gap-3">
          {(['album', 'artist'] as const).map((k) => (
            <button key={k} className={`${button} ${by === k ? 'underline' : ''}`} onClick={() => setBy(k)}>
              {translateUi(k === 'album' ? 'offline_page.group_by_album' : 'offline_page.group_by_artist')}
            </button>
          ))}
        </div>
      </div>
      {!items.length && <EmptyState title={translateUi("offline_page.nothing_saved_yet")} detail={translateUi("offline_page.use_save_for_offline_on")} />}
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
              <button className={button} onClick={() => playList(group.items.map(trackOf), 0, { source: { title: group.name, type: 'album', id: group.key }, fallbackArtwork: null, fallbackArtists: [] })}>{translateUi("offline_page.play")}</button>
              <button className={button} onClick={() => void removeOfflineCopies(group.items.map((i) => i.videoId))}>{translateUi("offline_page.remove_all")}</button>
            </div>
            <ul>
              {group.items.map((item) => (
                <li key={item.videoId} className="flex items-center gap-3 py-1.5">
                  <span className="min-w-0 grow truncate type-body-reduced">{item.title}</span>
                  <span className="type-callout text-secondary">{formatBytes(item.bytes)}</span>
                  <button className={button} onClick={() => void removeOfflineCopies([item.videoId])}>{translateUi("offline_page.remove")}</button>
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
    <section className="mt-8" aria-label={translateUi("offline_page.check_files")}>
      <h2 className="mb-2 type-subhead-emphasized uppercase text-secondary">{translateUi("offline_page.check_files")}</h2>
      <div className={card}>
        <button className={button} onClick={() => void check()}>{translateUi("offline_page.check_offline_files")}</button>
        {report && (
          <p className="mt-2 text-secondary" role="status">
            {problems
              ? report.orphans.length && report.broken.length
                ? translateUi('offline_page.audit_problems', {
                    files: translateUi('offline_page.audit_stray_files', { count: report.orphans.length }),
                    bytes: formatBytes(report.orphanBytes),
                    songs: translateUi('offline_page.audit_broken_songs', { count: report.broken.length }),
                  })
                : report.orphans.length
                  ? translateUi('offline_page.audit_stray_only', { files: translateUi('offline_page.audit_stray_files', { count: report.orphans.length }), bytes: formatBytes(report.orphanBytes) })
                  : translateUi('offline_page.audit_broken_only', { songs: translateUi('offline_page.audit_broken_songs', { count: report.broken.length }) })
              : translateUi('offline_page.audit_healthy', { bytes: formatBytes(report.keptBytes) })}{' '}
            {problems > 0 && (
              <button
                className={button}
                onClick={() =>
                  void repairOffline(report, rt.store, rt.files).then(({ files, records }) => {
                    rt.bus.emit();
                    setReport(null);
                    setFixed(translateUi('offline_page.audit_repair_complete', {
                      files: translateUi('offline_page.audit_file_count', { count: files }),
                      records: translateUi('offline_page.audit_record_count', { count: records }),
                    }));
                  })
                }
              >{translateUi("offline_page.clean_up")}</button>
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
      <PageHeader title={translateUi("offline_page.offline")} />
      <div className="mx-(--body-gutter) mt-5 mb-[120px] max-w-[720px]">
        {!supported ? (
          <EmptyState title={translateUi("offline_page.not_available_in_this_browser")} detail={translateUi("offline_page.offline_copies_need_the_browser")} />
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
