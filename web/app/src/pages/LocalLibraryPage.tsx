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
  { id: 'songs', label: 'Songs' },
  { id: 'artists', label: 'Artists' },
  { id: 'albums', label: 'Albums' },
  { id: 'genres', label: 'Genres' },
  { id: 'folders', label: 'Folders' },
  { id: 'playlists', label: 'Playlists' },
] as const;
type TabId = (typeof TABS)[number]['id'];

const button = 'type-callout-emphasized text-key hover:underline disabled:opacity-50';
const card = 'mx-(--body-gutter) mb-3 flex items-center gap-3 rounded-[12px] bg-quinary px-4 py-3 type-callout';

const ACCESS_TEXT: Record<RootAccess, string> = {
  granted: 'Connected',
  files: 'Connected for this visit',
  prompt: 'Needs your permission',
  offline: 'Not connected',
};

function FolderRow({ root, access, onPick }: { root: LibraryRootRow; access: RootAccess; onPick: (rootId: string) => void }) {
  const scanning = useScan((s) => s.scanning && s.rootId === root.id);
  const connected = access === 'granted' || access === 'files';
  return (
    <li className="flex items-center gap-3 py-2">
      <div className="min-w-0 flex-1">
        <div className="truncate type-body-emphasized text-primary">{root.name}</div>
        <div className="truncate type-callout text-secondary">
          {ACCESS_TEXT[access]}
          {root.scannedAt ? ` · indexed ${new Date(root.scannedAt).toLocaleDateString()}` : ''}
        </div>
      </div>
      {connected ? (
        <button className={button} disabled={scanning} onClick={() => void scanRoot(root.id)}>
          {scanning ? 'Indexing…' : 'Rescan'}
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
        >
          Reconnect folder
        </button>
      ) : (
        <button className={button} onClick={() => onPick(root.id)}>
          Reconnect folder
        </button>
      )}
      <button className="type-callout text-secondary hover:underline disabled:opacity-50" disabled={scanning} onClick={() => void removeFolder(root.id)}>
        Remove
      </button>
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
    if ('duplicate' in added) setNotice(`That folder is already part of “${added.duplicate}”.`);
    else void scanRoot(added.id);
  };
  const onFiles = async (files: File[]) => {
    const root = await adoptPickedFiles(files, reconnectFor.current);
    reconnectFor.current = undefined;
    if (!root) setNotice('That isn’t the folder that was added. Pick the same folder again.');
    else if ('duplicate' in root) setNotice(`“${root.duplicate}” is already added. Use Reconnect folder on it to pick it again.`);
    else void scanRoot(root.id);
  };

  const pendingReview = review.data?.length ?? 0;
  const body = tab === 'playlists' ? <PlaylistsTab /> : !loaded || loading ? null : items.length === 0 ? (
    <div className={stateStyles.state}>
      <p className={stateStyles.title}>{roots.length ? 'No songs found yet' : 'Add your music'}</p>
      <p className={stateStyles.detail}>
        {roots.length
          ? 'Connect your folders and rescan, or add another folder.'
          : 'Pick a folder with your music files. Songs stay on this device and play without an account.'}
      </p>
      <button className={stateStyles.button} onClick={() => void onAdd()}>
        Add Folder
      </button>
    </div>
  ) : tab === 'songs' ? (
    <TrackList tracks={items.map((i) => i.track)} variant="playlist" source={{ title: 'On This Device', type: 'library', id: 'local' }} />
  ) : openGroup ? (
    <>
      <div className="mx-(--body-gutter) mb-2 flex items-center gap-3">
        <button className={button} onClick={() => setParams({ tab })}>
          ‹ {TABS.find((t) => t.id === tab)!.label}
        </button>
        <h2 className="type-title-3-emphasized">{openGroup.title || 'Unknown'}</h2>
      </div>
      <TrackList
        tracks={openGroup.songs.map((i) => i.track)}
        variant="playlist"
        source={{ title: openGroup.title || 'On This Device', type: 'library', id: `local:${tab}:${openGroup.key}` }}
      />
    </>
  ) : groups && groups.length ? (
    <GroupList groups={groups} round={tab === 'artists'} onOpen={(key) => setParams({ tab, group: key })} />
  ) : (
    <EmptyState title="Nothing here yet" />
  );

  return (
    <>
      <PageHeader title="On This Device" />
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
          <button className={button} onClick={() => setNotice(null)}>Dismiss</button>
        </div>
      )}
      {scan.scanning && (
        <div className={card} role="status">
          <span className="flex-1">Indexing your music… {scan.seen} files seen{scan.parsed ? `, ${scan.parsed} read` : ''}.</span>
        </div>
      )}
      {scan.error && !scan.scanning && (
        <div className={card} role="alert">
          <span className="flex-1">{scan.error}</span>
        </div>
      )}
      {identify.running && (
        <div className={card} role="status">
          <span className="flex-1">Finding these songs on YouTube Music… {identify.done} of {identify.total}</span>
          <button className={button} onClick={cancelIdentify}>Stop</button>
        </div>
      )}
      {identify.error && !identify.running && (
        <div className={card} role="alert">
          <span className="flex-1">Couldn’t reach YouTube Music to identify songs: {identify.error}</span>
          <button className={button} onClick={() => void startIdentify(searchSongs)}>Try Again</button>
        </div>
      )}
      {pendingReview > 0 && (
        <div className={card}>
          <span className="flex-1">{pendingReview} {pendingReview === 1 ? 'song needs' : 'songs need'} a look.</span>
          <button className={button} onClick={() => setReviewOpen(true)}>Review</button>
        </div>
      )}
      {reviewOpen && <ReviewSheet onClose={() => setReviewOpen(false)} />}

      <nav className="mx-(--body-gutter) mb-3 flex flex-wrap gap-1" aria-label="Library sections">
        {TABS.map((t) => (
          <button
            key={t.id}
            aria-pressed={tab === t.id}
            onClick={() => setParams({ tab: t.id })}
            className="h-8 rounded-pill px-3.5 type-callout-emphasized text-secondary hover:bg-quinary aria-pressed:bg-nav-selected aria-pressed:text-primary"
          >
            {t.label}
          </button>
        ))}
        <span className="flex-1" />
        <button className={button} onClick={() => void onAdd()}>Add Folder</button>
      </nav>

      {roots.length > 0 && tab === 'folders' && !openGroup && (
        <ul className="mx-(--body-gutter) mb-4 divide-y divide-divider rounded-[12px] bg-quinary px-4" aria-label="Your folders">
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
