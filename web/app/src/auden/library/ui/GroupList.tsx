import { useVirtualizer } from '@tanstack/react-virtual';
import { useEffect, useRef, useState } from 'react';
import { Artwork } from '@/components/music/Artwork';
import type { LocalGroup } from '../groups';
import type { LocalItem } from './data';

const ROW = 60;

/** Artists / Albums / Genres / Folders: one virtualized column, so thousands of groups stay light. */
export function GroupList({ groups, round, onOpen }: { groups: LocalGroup<LocalItem>[]; round?: boolean; onOpen: (key: string) => void }) {
  const container = useRef<HTMLDivElement>(null);
  const [scroller, setScroller] = useState<HTMLElement | null>(null);
  useEffect(() => setScroller(document.getElementById('scrollable-page')), []);
  const virtualizer = useVirtualizer({
    count: groups.length,
    getScrollElement: () => scroller,
    estimateSize: () => ROW,
    overscan: 8,
    scrollMargin: container.current?.offsetTop ?? 0,
  });
  return (
    <div ref={container} className="mx-(--body-gutter)" role="list">
      <div style={{ height: virtualizer.getTotalSize(), position: 'relative' }}>
        {virtualizer.getVirtualItems().map((v) => {
          const group = groups[v.index]!;
          const art = group.songs.find((s) => s.track.thumbnail)?.track.thumbnail ?? null;
          return (
            <button
              key={group.key}
              role="listitem"
              onClick={() => onOpen(group.key)}
              className="absolute inset-x-0 top-0 flex h-[60px] items-center gap-3 rounded-[12px] px-2 text-start hover:bg-tracklist-hover"
              style={{ transform: `translateY(${v.start - virtualizer.options.scrollMargin}px)` }}
            >
              <div className="w-11 shrink-0">
                <Artwork src={art} displayWidth={44} radius={round ? '50%' : 'var(--radius-artwork-small)'} />
              </div>
              <span className="min-w-0 flex-1">
                <span className="block truncate type-body-emphasized text-primary">{group.title || 'Unknown'}</span>
                <span className="block truncate type-callout text-secondary">
                  {group.songs.length} {group.songs.length === 1 ? 'song' : 'songs'}
                  {group.subtitle ? ` · ${group.subtitle}` : ''}
                </span>
              </span>
            </button>
          );
        })}
      </div>
    </div>
  );
}
