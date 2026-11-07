import { useEffect, useState } from 'react';
import { getOfflineRuntime } from '../runtime';
import type { Followed, FollowedMap } from '../types';

const button = 'type-callout-emphasized text-key hover:underline disabled:opacity-50';

/** The saved-whole playlists, live (this tab's and other tabs' changes). */
export function useFollowed(): FollowedMap {
  const { store, bus } = getOfflineRuntime();
  const [map, setMap] = useState<FollowedMap>({});
  useEffect(() => {
    let alive = true;
    const load = () => void store.followed().then((m) => alive && setMap(m)).catch(() => {});
    load();
    const off = bus.subscribe(load);
    return () => {
      alive = false;
      off();
    };
  }, [store, bus]);
  return map;
}

/** Playlists that gained more songs than are fetched unasked: the question, kept in the store so a reload or another tab still sees it. */
export function KeepUpdatedPrompts() {
  const followed = useFollowed();
  const [busy, setBusy] = useState<string | null>(null);
  const asking = Object.entries(followed).filter((e): e is [string, Followed & { ask: NonNullable<Followed['ask']> }] => !!e[1].ask);
  if (!asking.length) return null;
  return (
    <section className="mb-3" aria-label="New songs waiting">
      {asking.map(([key, f]) => (
        <div key={key} className="mb-3 rounded-[12px] bg-quinary px-4 py-3 type-callout">
          <p>
            <strong>{f.title}</strong> gained {f.ask.ids.length} songs since you saved it. That is a lot to fetch without asking.
          </p>
          <p className="mt-2 flex gap-4">
            <button
              className={button}
              disabled={busy === key}
              onClick={() => {
                setBusy(key);
                void import('../keepUpdatedLive').then((m) => m.approveLive(key)).finally(() => setBusy(null));
              }}
            >
              Download them
            </button>
            <button
              className={button}
              disabled={busy === key}
              onClick={() => void Promise.all([import('../keepUpdated'), import('../keepUpdatedLive')]).then(([k, m]) => k.dismissAsk(m.liveDeps(), key))}
            >
              Not now
            </button>
          </p>
        </div>
      ))}
    </section>
  );
}

/**
 * The playlists being kept up to date, each with a way to stop: removing a playlist's copies does not stop it, as
 * the copies are listed by album and artist, not by playlist (Android stops when the download record goes).
 */
export function FollowedList() {
  const followed = useFollowed();
  const entries = Object.entries(followed);
  if (!entries.length) return null;
  return (
    <section className="mb-3" aria-label="Playlists kept up to date">
      <h2 className="mb-2 type-subhead-emphasized uppercase text-secondary">Kept up to date</h2>
      <ul className="rounded-[12px] bg-quinary">
        {entries.map(([key, f]) => (
          <li key={key} className="flex items-center gap-3 px-4 py-2.5 type-callout">
            <span className="min-w-0 flex-1 truncate">{f.title}</span>
            <span className="text-secondary">{f.toFolder ? 'with folder' : 'offline'}</span>
            <button
              className={button}
              onClick={() => void Promise.all([import('../keepUpdated'), import('../keepUpdatedLive')]).then(([k, m]) => k.unfollow(m.liveDeps(), key))}
            >
              Stop updating
            </button>
          </li>
        ))}
      </ul>
    </section>
  );
}
