import { t as translateUi } from '@/i18n';
import { useEffect } from 'react';
import { PrefetchLink } from '@/components/PrefetchLink';

/** A page-sized message, centred: also the Spotify page's sign-in invitation. */
export const stateStyles = {
  state: 'mx-auto my-20 flex max-w-[420px] flex-col items-center gap-2 px-(--body-gutter) text-center',
  title: 'type-title-2-emphasized',
  detail: 'type-body-tall text-secondary',
  button: 'mt-3 inline-flex h-8 items-center rounded-pill bg-key-bg px-4 type-body-emphasized text-white',
};

/** In place of a YouTube page when the extension isn't installed (prompt §1): an invitation, not an error. */
export function NeedsExtension({ what = 'YouTube Music' }: { what?: string }) {
  const resource = what === 'your library'
    ? translateUi('states.your_library')
    : what === 'your Spotify playlists'
      ? translateUi('states.your_spotify_playlists')
      : what === 'and search YouTube Music'
        ? translateUi('states.search_youtube_music')
        : translateUi('states.youtube_music');
  return (
    <div className={stateStyles.state}>
      <p className={stateStyles.title}>{translateUi('states.browse_with_the_auden_extension', { what: resource })}</p>
      <p className={stateStyles.detail}>{translateUi("states.the_extension_lets_this_page")}</p>
      <PrefetchLink to="/extension" className={stateStyles.button}>{translateUi("states.get_the_extension")}</PrefetchLink>
    </div>
  );
}

export function LoadError({ error, onRetry }: { error: unknown; onRetry: () => void }) {
  useEffect(() => {
    console.error('Auden page load failed', error);
  }, [error]);
  return (
    <div className={stateStyles.state}>
      <p className={stateStyles.title}>{translateUi("states.couldn_t_load_this_page")}</p>
      <p className={stateStyles.detail}>{translateUi('states.page_load_error_detail')}</p>
      <button className={stateStyles.button} onClick={onRetry}>{translateUi("states.try_again")}</button>
    </div>
  );
}

export function EmptyState({ title, detail }: { title: string; detail?: string }) {
  return (
    <div className={stateStyles.state}>
      <p className={stateStyles.title}>{title}</p>
      {detail && <p className={stateStyles.detail}>{detail}</p>}
    </div>
  );
}

/** A later page of a long list failed: what's loaded stays, with a way to try again. */
export function LoadMoreError({ onRetry }: { onRetry: () => void }) {
  return (
    <div className="flex items-center justify-center gap-2.5 px-(--body-gutter) pt-6 pb-10 type-callout text-secondary">
      <span>{translateUi("states.couldn_t_load_more")}</span>
      <button className="type-callout-emphasized text-key" onClick={onRetry}>{translateUi("states.try_again")}</button>
    </div>
  );
}
