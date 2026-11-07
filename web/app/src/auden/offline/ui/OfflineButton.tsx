import { useState } from 'react';
import { detailButton } from '@/components/music/DetailHeader';
import type { Track } from '@/data/music/types';
import { saveForOffline } from '../actions';
import type { Followed } from '../types';
import { useOfflineAvailable } from '../hooks';
import { useFolderOffer } from '../folder/hooks';
import { announce } from '../folder/report';
import { saveSongsToFolder } from '../folder/state';

/** The playlist a button saves whole, so its new songs are fetched too ("Keep playlists updated"). */
export interface Collection {
  kind: Followed['kind'];
  id: string;
  title: string;
}

/**
 * "Save for Offline" in an album or playlist header: queues every song that can be kept. With a [collection] the
 * playlist is then watched for new songs; its [loadAll] reads the whole list (a playlist streams in as you scroll), so
 * what was never loaded is not mistaken for new later.
 */
export function OfflineButton({ tracks, collection, loadAll }: { tracks: readonly Track[]; collection?: Collection; loadAll?: () => Promise<Track[]> }) {
  const available = useOfflineAvailable();
  const folder = useFolderOffer(false);
  const [note, setNote] = useState<string | null>(null);
  if (!available || !tracks.length) return null;
  return (
    <>
    <button
      className={detailButton.pill}
      title="Fetches these songs into this browser so they play without a connection. Watch progress under Offline."
      onClick={() => {
        void (async () => {
          const all = loadAll ? await loadAll() : tracks;
          const { added } = await saveForOffline(all);
          if (collection) await followCollection(collection, all, false);
          setNote(added ? `${added} queued` : 'Already saved');
          setTimeout(() => setNote(null), 3000);
        })();
      }}
    >
      {note ?? 'Save for Offline'}
    </button>
    {folder && (
      <button
        className={detailButton.pill}
        title="Saves these songs as files into your chosen folder (downloading first when needed)."
        onClick={() =>
          void (async () => {
            const all = loadAll ? await loadAll() : tracks;
            const outcome = await saveSongsToFolder(all);
            // Only once the folder took the request: a refusal (no folder, no access) saved nothing.
            if (collection && outcome.kind === 'ok') await followCollection(collection, all, true);
            announce(outcome);
          })()
        }
      >
        Save to Folder
      </button>
    )}
    </>
  );
}

/** Records the playlist as saved whole (loaded late: the sync code is not needed until a button is pressed). */
async function followCollection(collection: Collection, tracks: readonly Track[], toFolder: boolean): Promise<void> {
  const [{ follow }, { liveDeps }] = await Promise.all([import('../keepUpdated'), import('../keepUpdatedLive')]);
  await follow(liveDeps(), { ...collection, tracks, toFolder });
}
