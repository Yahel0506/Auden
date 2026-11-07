import { useEffect, useState } from 'react';
import { useDj } from '../session';

/** How long a line with no voice stays over the player before it goes. */
export const CAPTION_MS = 8000;

/**
 * The words of a set that has no voice (no clip: the DJ spoke in text only), over the usual player while its first song
 * plays. When there is a voice the words belong to the DJ's screen, which shows them and is gone when a song plays, so this
 * shows nothing then.
 */
export function DjCaption() {
  const line = useDj((s) => (s.phase === 'on' && s.wordsOnly ? s.line : null));
  const [shown, setShown] = useState<string | null>(null);
  useEffect(() => {
    setShown(line);
    if (!line) return;
    const id = setTimeout(() => setShown(null), CAPTION_MS);
    return () => clearTimeout(id);
  }, [line]);
  if (!shown) return null;
  return (
    <div className="pointer-events-none absolute inset-x-0 top-16 z-1 flex animate-fade-in flex-col items-center px-8 text-center" role="status" aria-live="polite">
      <p className="max-w-[min(640px,100%)] type-title-3 text-primary">{shown}</p>
    </div>
  );
}
