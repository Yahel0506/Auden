import { useEffect, useState, type ImgHTMLAttributes } from 'react';

interface RetryImgProps extends Omit<ImgHTMLAttributes<HTMLImageElement>, 'src'> {
  /** The sized URL asked of the CDN. */
  src: string;
  /** What to ask for when the sized URL keeps failing: the artwork's own URL. Defaults to [src]. */
  fallbackSrc?: string;
}

const RETRY_AFTER_MS = [1500, 3500];

/**
 * An image that asks again when the CDN refuses. googleusercontent answers bursts of uncached sizes with 429
 * (lib/artwork.ts), and a cover that fails once stays blank for the rest of the page's life: every player
 * surface (the pill, the full-screen cover, its glow) goes through here so a refused request is retried
 * after a pause, then once with the artwork's own URL, before it is let go (onError).
 */
export function RetryImg({ src, fallbackSrc, onError, onLoad, ...rest }: RetryImgProps) {
  const [attempt, setAttempt] = useState(0);
  const [failed, setFailed] = useState(false);
  // A new cover starts again from the first attempt.
  useEffect(() => {
    setAttempt(0);
    setFailed(false);
  }, [src]);
  useEffect(() => {
    if (!failed || attempt >= RETRY_AFTER_MS.length) return;
    const timer = setTimeout(() => {
      setAttempt((a) => a + 1);
      setFailed(false);
    }, RETRY_AFTER_MS[attempt]);
    return () => clearTimeout(timer);
  }, [failed, attempt]);
  const url = attempt >= RETRY_AFTER_MS.length && fallbackSrc ? fallbackSrc : src;
  return (
    <img
      {...rest}
      // A fresh element per attempt: a failed one never retries by itself.
      key={`${url}#${attempt}`}
      src={url}
      onLoad={onLoad}
      onError={(e) => {
        if (attempt < RETRY_AFTER_MS.length) setFailed(true);
        else onError?.(e);
      }}
    />
  );
}
