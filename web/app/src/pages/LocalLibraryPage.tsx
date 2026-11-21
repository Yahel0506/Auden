import { date, number, t as translateUi } from '@/i18n';
import { useEffect, useMemo, useRef, useState } from 'react';
import { useSearchParams } from 'react-router';
import { useShallow } from 'zustand/react/shallow';
import { PageHeader } from '@/components/PageHeader';
import { EmptyState, stateStyles } from '@/components/music/States';
import { TrackList } from '@/components/music/TrackList';
import { searchSongs } from '@/data/music/api';
import { albumsOf, artistsOf, foldersOf, genresOf } from '@/auden/library/groups';
import { cancelIdentify, needsIdentifying, startIdentify, useIdentify } from '@/auden/library/identify/identifier';
import { adoptPickedFiles, addFolder, fsaSupported, reconnect, refreshRoots, removeFolder, useRoots, type RootAccess } from '@/auden/library/roots';
import { scanRoot, useScan } from '@/auden/library/scan/indexer';
import { useLocalItems, useReviewRows } from '@/auden/library/ui/data';
import { GroupList } from '@/auden/library/ui/GroupList';
import { PlaylistsTab } from '@/auden/playlists/ui/PlaylistsTab';
import { ReviewSheet } from '@/auden/library/ui/ReviewSheet';
import { tracks as trackDao } from '@/auden/db/dao';
import type { LibraryRootRow } from '@/auden/db/schema';

const TABS = [
  { id: 'songs', key: 'local_library_page.tab_songs' },
  { id: 'artists', key: 'local_library_page.tab_artists' },
  { id: 'albums', key: 'local_library_page.tab_albums' },
  { id: 'genres', key: 'local_library_page.tab_genres' },
  { id: 'folders', key: 'local_library_page.tab_folders' },
  { id: 'playlists', key: 'local_library_page.tab_playlists' },
] as const;
type TabId = (typeof TABS)[number]['id'];
const tabLabel = (tab: TabId) => translateUi(TABS.find((item) => item.id === tab)!.key as keyof typeof import('@/i18n.en.json'));

const button = 'type-callout-emphasized text-key hover:underline disabled:opacity-50';
const card = 'mx-(--body-gutter) mb-3 flex items-center gap-3 rounded-[12px] bg-quinary px-4 py-3 type-callout';

const accessText = (access: RootAccess) => translateUi(({
  granted: 'local_library_page.access_connected',
  files: 'local_library_page.access_connected_for_visit',
  prompt: 'local_library_page.access_permission_needed',
  offline: 'local_library_page.access_not_connected',
})[access] as keyof typeof import('@/i18n.en.json'));

function FolderRow({ root, access, onPick }: { root: LibraryRootRow; access: RootAccess; onPick: (rootId: string) => void }) {
  const scanning = useScan((s) => s.scanning && s.rootId === root.id);
  const connected = access === 'granted' || access === 'files';
  return (
    <li className="flex items-center gap-3 py-2">
      <div className="min-w-0 flex-1">
        <div className="truncate type-body-emphasized text-primary">{root.name}</div>
        <div className="truncate type-callout text-secondary">
          {accessText(access)}
          {root.scannedAt ? ` · ${translateUi('local_library_page.indexed_date', { date: date(new Date(root.scannedAt)) })}` : ''}
        </div>
      </div>
      {connected ? (
        <button className={button} disabled={scanning} onClick={() => void scanRoot(root.id)}>
          {scanning ? translateUi("pages_locallibrarypage_indexing") : translateUi('local_library_page.rescan')}
        </button>
      ) : root.kind === 'fsa' ? (
        <button
          className={button}
          onClick={() =>
            // requestPermission needs this click: it must be the first thing awaited.
            void reconnect(root).then((ok) => {
              if (ok) void scanRoot(root.id);
            })
          }
        >{translateUi("local_library_page.reconnect_folder")}</button>
      ) : (
        <button className={button} onClick={() => onPick(root.id)}>{translateUi("local_library_page.reconnect_folder")}</button>
      )}
      <button className="type-callout text-secondary hover:underline disabled:opacity-50" disabled={scanning} onClick={() => void removeFolder(root.id)}>{translateUi("local_library_page.remove")}</button>
    </li>
  );
}

/** Songs on this device, browsable without any account. */
export default function LocalLibraryPage() {
  const [params, setParams] = useSearchParams();
  const tab = (TABS.find((t) => t.id === params.get('tab'))?.id ?? 'songs') as TabId;
  const groupKey = params.get('group');
  const { items, loading } = useLocalItems();
  const roots = useRoots((s) => s.roots);
  const access = useRoots((s) => s.access);
  const loaded = useRoots((s) => s.loaded);
  const scan = useScan(useShallow((s) => ({ scanning: s.scanning, error: s.error, seen: s.seen, parsed: s.parsed })));
  const identify = useIdentify(useShallow((s) => ({ running: s.running, error: s.error, done: s.done, total: s.total })));
  const review = useReviewRows();
  const [reviewOpen, setReviewOpen] = useState(false);
  const [notice, setNotice] = useState<string | null>(null);
  const input = useRef<HTMLInputElement>(null);
  const reconnectFor = useRef<string | undefined>(undefined);

  useEffect(() => {
    if (scan.error) console.error('Auden local library scan failed', scan.error);
    if (identify.error) console.error('Auden YouTube Music identification failed', identify.error);
  }, [identify.error, scan.error]);

  // Refresh what each folder allows, then bring connected folders up to date (incremental: unchanged files aren't read).
  useEffect(() => {
    void refreshRoots().then(() => {
      const { roots: rs, access: ac } = useRoots.getState();
      for (const r of rs) if (ac[r.id] === 'granted') void scanRoot(r.id);
    });
  }, []);

  // After an index run, look the new files up on YouTube Music (resumable; stops on repeated failures).
  const wasScanning = useRef(false);
  useEffect(() => {
    if (wasScanning.current && !scan.scanning && !scan.error) {
      void trackDao.all().then((rows) => {
        if (rows.some(needsIdentifying)) void startIdentify(searchSongs);
      });
    }
    wasScanning.current = scan.scanning;
  }, [scan.scanning, scan.error]);

  const groups = useMemo(() => {
    switch (tab) {
      case 'artists': return artistsOf(items);
      case 'albums': return albumsOf(items);
      case 'genres': return genresOf(items);
      case 'folders': return foldersOf(items);
      default: return null;
    }
  }, [tab, items]);
  const openGroup = groups?.find((g) => g.key === groupKey) ?? null;

  const onAdd = async () => {
    if (!fsaSupported()) {
      reconnectFor.current = undefined;
      input.current?.click();
      return;
    }
    const added = await addFolder();
    if (!added) return;
    if ('duplicate' in added) setNotice(translateUi('local_library_page.folder_already_added', { name: added.duplicate }));
    else void scanRoot(added.id);
  };
  const onFiles = async (files: File[]) => {
    const root = await adoptPickedFiles(files, reconnectFor.current);
    reconnectFor.current = undefined;
    if (!root) setNotice(translateUi('local_library_page.pick_the_same_folder_again'));
    else if ('duplicate' in root) setNotice(translateUi('local_library_page.folder_already_added_reconnect', { name: root.duplicate }));
    else void scanRoot(root.id);
  };

  const pendingReview = review.data?.length ?? 0;
  const body = tab === 'playlists' ? <PlaylistsTab /> : !loaded || loading ? null : items.length === 0 ? (
    <div className={stateStyles.state}>
      <p className={stateStyles.title}>{roots.length ? translateUi("pages_locallibrarypage_no_songs_found_yet") : translateUi("pages_locallibrarypage_add_your_music")}</p>
      <p className={stateStyles.detail}>
        {roots.length
          ? translateUi("pages_locallibrarypage_connect_your_folders_and_rescan")
          : translateUi("pages_locallibrarypage_pick_a_folder_with_your")}
      </p>
      <button className={stateStyles.button} onClick={() => void onAdd()}>{translateUi("local_library_page.add_folder")}</button>
    </div>
  ) : tab === 'songs' ? (
    <TrackList tracks={items.map((i) => i.track)} variant="playlist" source={{ title: translateUi('local_library_page.on_this_device'), type: 'library', id: 'local' }} />
  ) : openGroup ? (
    <>
      <div className="mx-(--body-gutter) mb-2 flex items-center gap-3">
        <button className={button} onClick={() => setParams({ tab })}>
          ‹ {tabLabel(tab)}
        </button>
        <h2 className="type-title-3-emphasized">{openGroup.title || translateUi('group_list.unknown')}</h2>
      </div>
      <TrackList
        tracks={openGroup.songs.map((i) => i.track)}
        variant="playlist"
        source={{ title: openGroup.title || translateUi('local_library_page.on_this_device'), type: 'library', id: `local:${tab}:${openGroup.key}` }}
      />
    </>
  ) : groups && groups.length ? (
    <GroupList groups={groups} round={tab === 'artists'} onOpen={(key) => setParams({ tab, group: key })} />
  ) : (
    <EmptyState title={translateUi("local_library_page.nothing_here_yet")} />
  );

  return (
    <>
      <PageHeader title={translateUi("local_library_page.on_this_device")} />
      <input
        ref={input}
        type="file"
        hidden
        multiple
        // @ts-expect-error webkitdirectory is non-standard but supported by Firefox, Chromium and Safari.
        webkitdirectory=""
        onChange={(e) => {
          const files = [...(e.target.files ?? [])];
          e.target.value = '';
          if (files.length) void onFiles(files);
        }}
      />
      {notice && (
        <div className={card} role="status">
          <span className="flex-1">{notice}</span>
          <button className={button} onClick={() => setNotice(null)}>{translateUi("local_library_page.dismiss")}</button>
        </div>
      )}
      {scan.scanning && (
        <div className={card} role="status">
          <span className="flex-1">{translateUi(scan.parsed ? 'local_library_page.index_progress_parsed' : 'local_library_page.index_progress', { seen: number(scan.seen), parsed: number(scan.parsed) })}</span>
        </div>
      )}
      {scan.error && !scan.scanning && (
        <div className={card} role="alert">
          <span className="flex-1">{translateUi('local_library_page.scan_failed')}</span>
        </div>
      )}
      {identify.running && (
        <div className={card} role="status">
          <span className="flex-1">{translateUi('local_library_page.identification_progress', { done: number(identify.done), total: number(identify.total) })}</span>
          <button className={button} onClick={cancelIdentify}>{translateUi("local_library_page.stop")}</button>
        </div>
      )}
      {identify.error && !identify.running && (
        <div className={card} role="alert">
          <span className="flex-1">{translateUi('local_library_page.identification_failed')}</span>
          <button className={button} onClick={() => void startIdentify(searchSongs)}>{translateUi("local_library_page.try_again")}</button>
        </div>
      )}
      {pendingReview > 0 && (
        <div className={card}>
          <span className="flex-1">{translateUi('local_library_page.pending_review_count', { count: pendingReview })}</span>
          <button className={button} onClick={() => setReviewOpen(true)}>{translateUi("local_library_page.review")}</button>
        </div>
      )}
      {reviewOpen && <ReviewSheet onClose={() => setReviewOpen(false)} />}

      <nav className="mx-(--body-gutter) mb-3 flex flex-wrap gap-1" aria-label={translateUi("local_library_page.library_sections")}>
        {TABS.map((t) => (
          <button
            key={t.id}
            aria-pressed={tab === t.id}
            onClick={() => setParams({ tab: t.id })}
            className="h-8 rounded-pill px-3.5 type-callout-emphasized text-secondary hover:bg-quinary aria-pressed:bg-nav-selected aria-pressed:text-primary"
          >
            {translateUi(t.key as keyof typeof import('@/i18n.en.json'))}
          </button>
        ))}
        <span className="flex-1" />
        <button className={button} onClick={() => void onAdd()}>{translateUi("local_library_page.add_folder")}</button>
      </nav>

      {roots.length > 0 && tab === 'folders' && !openGroup && (
        <ul className="mx-(--body-gutter) mb-4 divide-y divide-divider rounded-[12px] bg-quinary px-4" aria-label={translateUi("local_library_page.your_folders")}>
          {roots.map((r) => (
            <FolderRow
              key={r.id}
              root={r}
              access={access[r.id] ?? 'offline'}
              onPick={(id) => {
                reconnectFor.current = id;
                input.current?.click();
              }}
            />
          ))}
        </ul>
      )}
      {body}
    </>
  );
}
