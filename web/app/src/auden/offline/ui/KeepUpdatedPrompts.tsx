import { t as translateUi } from '@/i18n';
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
    <section className="mb-3" aria-label={translateUi("keep_updated_prompts.new_songs_waiting")}>
      {asking.map(([key, f]) => (
        <div key={key} className="mb-3 rounded-[12px] bg-quinary px-4 py-3 type-callout">
          <p>
            {translateUi("keep_updated_prompts.gained_songs_since_saved", { title: f.title, count: f.ask.ids.length })}
          </p>
          <p className="mt-2 flex gap-4">
            <button
              className={button}
              disabled={busy === key}
              onClick={() => {
                setBusy(key);
                void import('../keepUpdatedLive').then((m) => m.approveLive(key)).finally(() => setBusy(null));
              }}
            >{translateUi("keep_updated_prompts.download_them")}</button>
            <button
              className={button}
              disabled={busy === key}
              onClick={() => void Promise.all([import('../keepUpdated'), import('../keepUpdatedLive')]).then(([k, m]) => k.dismissAsk(m.liveDeps(), key))}
            >{translateUi("keep_updated_prompts.not_now")}</button>
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
    <section className="mb-3" aria-label={translateUi("keep_updated_prompts.playlists_kept_up_to_date")}>
      <h2 className="mb-2 type-subhead-emphasized uppercase text-secondary">{translateUi("keep_updated_prompts.kept_up_to_date")}</h2>
      <ul className="rounded-[12px] bg-quinary">
        {entries.map(([key, f]) => (
          <li key={key} className="flex items-center gap-3 px-4 py-2.5 type-callout">
            <span className="min-w-0 flex-1 truncate">{f.title}</span>
            <span className="text-secondary">{translateUi(f.toFolder ? 'keep_updated_prompts.with_folder' : 'keep_updated_prompts.offline')}</span>
            <button
              className={button}
              onClick={() => void Promise.all([import('../keepUpdated'), import('../keepUpdatedLive')]).then(([k, m]) => k.unfollow(m.liveDeps(), key))}
            >{translateUi("keep_updated_prompts.stop_updating")}</button>
          </li>
        ))}
      </ul>
    </section>
  );
}
