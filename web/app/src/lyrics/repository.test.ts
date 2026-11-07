// @vitest-environment happy-dom
import { describe, expect, it, vi } from 'vitest';
import { line } from './model';
import { lookupLyrics } from './repository';
import { useLyricsSettings } from './settings';

const lrclibCalls = vi.hoisted(() => vi.fn(async (..._args: unknown[]) => ({ lines: [{ timeMs: 1000, text: 'line timed', words: [] }], ttml: '<tt/>' }) as never));
const kugouCalls = vi.hoisted(() => vi.fn(async (..._args: unknown[]) => null));
vi.mock('./http', () => ({ lyricsGet: async () => null }));
vi.mock('./providers', () => ({
  lrclib: (...args: unknown[]) => lrclibCalls(...args),
  unison: () => new Promise(() => {}),
  kugou: (...args: unknown[]) => kugouCalls(...args),
  lyricsPlus: async () => ({
    lines: [line(1000, 'word', { words: [{ startMs: 1000, endMs: 1500, text: 'word', syllables: [] }] })],
    ttml: '<tt/>',
  }),
}));

describe('lookupLyrics', () => {
  it('asks again when "prefer syllable timing" changes', async () => {
    useLyricsSettings.setState({ order: ['lrclib', 'lyrics-plus'], disabled: [], preferSyllables: false });
    const track = { videoId: 'abcdefghijk', title: 'Song', artist: 'Artist', durationMs: 180_000, album: null };

    expect((await lookupLyrics(track))?.source).toBe('lrclib');
    useLyricsSettings.setState({ preferSyllables: true });
    expect((await lookupLyrics(track))?.source).toBe('lyrics-plus');
  });

  it('settles on what has arrived when a slower provider hangs', async () => {
    useLyricsSettings.setState({ order: ['unison', 'lrclib'], disabled: [], preferSyllables: false });
    const track = { videoId: 'settleSettle', title: 'S', artist: 'A', durationMs: 1000, album: null };
    const found = await lookupLyrics(track, { settleAfterMs: 50 });
    expect(found?.source).toBe('lrclib');
  });

  it('gives up with nothing when nothing has arrived, instead of hanging', async () => {
    useLyricsSettings.setState({ order: ['unison'], disabled: [], preferSyllables: false });
    const track = { videoId: 'hungHungHun', title: 'S', artist: 'A', durationMs: 1000, album: null };
    expect(await lookupLyrics(track, { settleAfterMs: 30 })).toBeNull();
  });

  it('a fresh lookup asks the providers again instead of answering from memory', async () => {
    useLyricsSettings.setState({ order: ['kugou'], disabled: [], preferSyllables: false });
    kugouCalls.mockClear();
    const track = { videoId: 'freshFresh1', title: 'S', artist: 'A', durationMs: 1000, album: null };
    expect(await lookupLyrics(track)).toBeNull();
    expect(await lookupLyrics(track)).toBeNull();
    expect(kugouCalls).toHaveBeenCalledTimes(1); // the second came from memory
    await lookupLyrics(track, { fresh: true });
    expect(kugouCalls).toHaveBeenCalledTimes(2);
  });

  it('does not remember an answer cut short by the deadline', async () => {
    useLyricsSettings.setState({ order: ['unison', 'lrclib'], disabled: [], preferSyllables: false });
    const track = { videoId: 'cutShortCut', title: 'S', artist: 'A', durationMs: 1000, album: null };
    lrclibCalls.mockClear();
    expect((await lookupLyrics(track, { settleAfterMs: 30 }))?.source).toBe('lrclib');
    expect((await lookupLyrics(track, { settleAfterMs: 30 }))?.source).toBe('lrclib');
    // Had the first answer been remembered, the second would not have asked lrclib again.
    expect(lrclibCalls).toHaveBeenCalledTimes(2);
  });
});
