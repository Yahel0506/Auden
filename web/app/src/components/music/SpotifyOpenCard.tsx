import { useState } from 'react';
import { useNavigate } from 'react-router';
import { Artwork } from './Artwork';

/** A saved Spotify album or artist: it opens on YouTube Music once that page is found, or says it is not there. */
export function SpotifyOpenCard({
  title,
  subtitle,
  imageUrl,
  round,
  findHref,
}: {
  title: string;
  subtitle: string;
  imageUrl: string | null;
  round?: boolean;
  findHref: () => Promise<string | null>;
}) {
  const navigate = useNavigate();
  const [state, setState] = useState<'idle' | 'looking' | 'missing' | 'failed'>('idle');
  return (
    <button
      className="flex w-full min-w-0 flex-col text-start"
      disabled={state === 'looking'}
      onClick={() => {
        setState('looking');
        findHref()
          .then((href) => (href ? navigate(href) : setState('missing')))
          .catch(() => setState('failed'))
          .finally(() => setState((s) => (s === 'looking' ? 'idle' : s)));
      }}
    >
      <Artwork src={imageUrl} displayWidth={200} radius={round ? '50%' : undefined} />
      <span className="mt-2 flex min-w-0 flex-col gap-px">
        <span className="truncate type-callout">{title}</span>
        <span className="truncate type-callout text-secondary">
          {state === 'looking' ? 'Looking on YouTube Music…' : state === 'missing' ? 'Not on YouTube Music' : state === 'failed' ? 'Couldn’t search YouTube Music' : subtitle}
        </span>
      </span>
    </button>
  );
}
