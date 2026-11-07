import { describe, expect, it, vi } from 'vitest';

vi.mock('@/playback/youtube/innertube', () => ({ getInnertube: vi.fn(), getMusicClient: vi.fn(), getSignedInnertube: vi.fn() }));

const { audioCutIn } = await import('./api');

/** A watch-queue cut as YouTube.js parses it, of the given music video type. */
const cut = (id: string, type: 'MUSIC_VIDEO_TYPE_ATV' | 'MUSIC_VIDEO_TYPE_OMV', album: string | null = null) => ({
  type: 'PlaylistPanelVideo',
  video_id: id,
  title: { toString: () => (type === 'MUSIC_VIDEO_TYPE_ATV' ? 'Song' : 'Song (Official Video)') },
  artists: [{ name: 'Artist', channel_id: 'UC1' }],
  album: album ? { name: album, id: 'MPREb_1' } : undefined,
  duration: { seconds: 200 },
  badges: [],
  thumbnail: [{ url: `https://img/${id}`, width: 120, height: 120 }],
  endpoint: { payload: { watchEndpointMusicSupportedConfigs: { watchEndpointMusicConfig: { musicVideoType: type } } } },
});
const pair = (primary: ReturnType<typeof cut>, counterpart: ReturnType<typeof cut>) => ({
  type: 'PlaylistPanelVideoWrapper',
  primary,
  counterpart: [counterpart],
});

describe('audioCutIn', () => {
  const rows = [
    pair(cut('other', 'MUSIC_VIDEO_TYPE_OMV'), cut('otherSong', 'MUSIC_VIDEO_TYPE_ATV', 'B')),
    pair(cut('v', 'MUSIC_VIDEO_TYPE_OMV'), cut('s', 'MUSIC_VIDEO_TYPE_ATV', 'Album')),
    cut('lonely', 'MUSIC_VIDEO_TYPE_OMV'),
    cut('plainSong', 'MUSIC_VIDEO_TYPE_ATV', 'C'),
  ];

  it('takes the song cut of the row holding the video, not another row’s', () => {
    expect(audioCutIn(rows, 'v')).toMatchObject({ videoId: 's', title: 'Song', album: { name: 'Album' } });
  });

  it('finds the row by either cut, with the song as its primary', () => {
    const flipped = [pair(cut('s', 'MUSIC_VIDEO_TYPE_ATV', 'Album'), cut('v', 'MUSIC_VIDEO_TYPE_OMV'))];
    expect(audioCutIn(flipped, 'v')?.videoId).toBe('s');
  });

  it('is null for a video with no song cut, for the song itself, and for an id not in the queue', () => {
    expect(audioCutIn(rows, 'lonely')).toBeNull();
    expect(audioCutIn(rows, 's')).toBeNull();
    expect(audioCutIn(rows, 'plainSong')).toBeNull();
    expect(audioCutIn(rows, 'missing')).toBeNull();
  });

  it('never trades a song for another song, nor a video for another video, in the same row', () => {
    // Two catalogue cuts (an explicit and a clean one): a song is never "converted".
    expect(audioCutIn([pair(cut('explicit', 'MUSIC_VIDEO_TYPE_ATV', 'A'), cut('clean', 'MUSIC_VIDEO_TYPE_ATV', 'A'))], 'clean')).toBeNull();
    // Two video cuts: no song to play.
    expect(audioCutIn([pair(cut('official', 'MUSIC_VIDEO_TYPE_OMV'), cut('lyric', 'MUSIC_VIDEO_TYPE_OMV'))], 'lyric')).toBeNull();
  });
});
