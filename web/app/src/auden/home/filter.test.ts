import { describe, expect, it } from 'vitest';
import type { MusicItem, Shelf } from '@/data/music/types';
import { isRecentShelf, withoutVideos } from './filter';

const item = (title: string, kind: MusicItem['kind'] = 'song'): MusicItem => ({
  kind,
  id: title,
  title,
  subtitle: '',
  artists: [],
  album: null,
  thumbnail: null,
  wide: kind === 'video',
  durationSec: null,
  explicit: false,
});
const shelf = (title: string, items: MusicItem[], layout: Shelf['layout'] = 'square'): Shelf => ({ title, strapline: null, layout, items, seeAll: null });

describe('withoutVideos (Android HomeVideoFilterTest)', () => {
  it('takes the videos out of a mixed shelf', () => {
    const out = withoutVideos([shelf('Quick picks', [item('song'), item('video', 'video')])]);
    expect(out[0]?.items.map((i) => i.title)).toEqual(['song']);
  });
  it('drops a shelf left empty', () => {
    expect(withoutVideos([shelf('Videos', [item('video', 'video')])])).toEqual([]);
  });
  it('drops a video-layout shelf even if a row was not marked a video', () => {
    expect(withoutVideos([shelf('Music videos', [item('unmarked')], 'video')])).toEqual([]);
  });
  it('leaves shelves without videos untouched (same objects)', () => {
    const shelves = [shelf('Albums', [item('album')])];
    expect(withoutVideos(shelves)[0]).toBe(shelves[0]);
  });
});

describe('isRecentShelf', () => {
  it('matches the feed’s own title however it is cased', () => {
    expect(isRecentShelf(shelf(' recently played ', []))).toBe(true);
    expect(isRecentShelf(shelf('Quick picks', []))).toBe(false);
  });
});
