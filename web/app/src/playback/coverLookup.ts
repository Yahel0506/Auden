import { useCallback, useEffect, useState, useSyncExternalStore, type RefCallback } from 'react';
import { fetchTrackLinks } from '@/data/music/api';
import { coversVersion, rememberCover, resolvedCoverOf, subscribeCovers } from '@/lib/resolvedCovers';

/**
 * The real cover of a queue or list row that has none (the phone sends no cover for a song whose art is a file on it),
 * found from YouTube Music by video id as the row appears. Asked two at a time, newest row first, and only a few dozen
 * at once, so a long queue does not turn into a burst of requests; a row that goes away before its turn is dropped.
 */
const CONCURRENCY = 2;
const MAX_PENDING = 40;
const RETRY_AFTER_MS = 60_000;
/** YouTube video ids: the only ones YouTube Music can be asked about (not a local file or a clip). */
const YOUTUBE_ID = /^[\w-]{11}$/;

type Lookup = (videoId: string) => Promise<{ thumbnail: string | null } | null>;
let lookup: Lookup = fetchTrackLinks;
const pending: string[] = [];
const active = new Set<string>();
const failedAt = new Map<string, number>();

/** Asks for the cover of [videoId]; returns what cancels the ask. */
export function requestCover(videoId: string): () => void {
  if (!YOUTUBE_ID.test(videoId) || resolvedCoverOf(videoId) || active.has(videoId)) return () => {};
  if (Date.now() - (failedAt.get(videoId) ?? -RETRY_AFTER_MS) < RETRY_AFTER_MS) return () => {};
  if (!pending.includes(videoId)) {
    pending.push(videoId);
    if (pending.length > MAX_PENDING) pending.shift();
  }
  pump();
  return () => {
    const at = pending.indexOf(videoId);
    if (at >= 0) pending.splice(at, 1);
  };
}

function pump(): void {
  while (active.size < CONCURRENCY && pending.length > 0) {
    const id = pending.pop()!;
    active.add(id);
    void lookup(id)
      .then((track) => (track?.thumbnail ? rememberCover(id, track.thumbnail) : void failedAt.set(id, Date.now())))
      .catch(() => void failedAt.set(id, Date.now()))
      .finally(() => {
        active.delete(id);
        pump();
      });
  }
}

/**
 * [src] when the row has one; else the cover found for the song, asked for only while the row is on screen (attach
 * [ref] to the row's element): a queue of a hundred songs asks for the dozen that can be seen, not all of them.
 */
export function useResolvedCover(videoId: string, src: string | null | undefined): { cover: string | null; ref: RefCallback<HTMLElement> } {
  useSyncExternalStore(subscribeCovers, coversVersion);
  const [element, setElement] = useState<HTMLElement | null>(null);
  const [visible, setVisible] = useState(false);
  const ref = useCallback<RefCallback<HTMLElement>>((node) => setElement(node), []);
  useEffect(() => {
    if (src || !element) return;
    // No IntersectionObserver (an old browser, a test): the row counts as seen.
    if (typeof IntersectionObserver === 'undefined') {
      setVisible(true);
      return;
    }
    const observer = new IntersectionObserver((entries) => setVisible(entries.some((e) => e.isIntersecting)), { rootMargin: '120px' });
    observer.observe(element);
    return () => observer.disconnect();
  }, [src, element]);
  useEffect(() => (src || !visible ? undefined : requestCover(videoId)), [videoId, src, visible]);
  return { cover: src ?? resolvedCoverOf(videoId), ref };
}

/** For tests: swap the lookup and forget the queue. */
export function setCoverLookup(fn: Lookup | null): void {
  lookup = fn ?? fetchTrackLinks;
  pending.length = 0;
  active.clear();
  failedAt.clear();
}
