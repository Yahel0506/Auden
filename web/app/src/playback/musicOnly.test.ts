// @vitest-environment happy-dom
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import type { Track } from '@/data/music/types';

const audioVersionOf = vi.fn<(videoId: string) => Promise<Track | null>>();
vi.mock('@/data/music/api', () => ({ audioVersionOf: (id: string) => audioVersionOf(id) }));

const {
  asMusicOnlyOf,
  clearMusicOnlyForTests,
  forgetExcept,
  keep,
  musicOnlyVersion,
  noteCuts,
  showsPicture,
  useMusicOnly,
  wantsMusicOnly,
} = await import('./musicOnly');

const track = (id: string, extra: Partial<Track> = {}): Track => ({
  videoId: id,
  title: id,
  artists: [{ name: 'Artist', id: null }],
  album: null,
  durationSec: 200,
  explicit: false,
  thumbnail: null,
  index: null,
  plays: null,
  ...extra,
});
const song = (id: string) => track(id, { album: { name: 'Album', id: 'MPREb_1' }, title: 'Song' });
const plain = { inParty: false, dj: false, playsLocally: false };
const flush = () => new Promise((resolve) => setTimeout(resolve, 0));

beforeEach(() => {
  localStorage.clear();
  clearMusicOnlyForTests();
  audioVersionOf.mockReset();
  useMusicOnly.setState({ preferMusicOnly: true });
});

afterEach(() => useMusicOnly.setState({ preferMusicOnly: false }));

describe('the setting', () => {
  it('is off by default and kept under auden:musiconly', () => {
    expect(useMusicOnly.getInitialState().preferMusicOnly).toBe(false);
    useMusicOnly.getState().setPreferMusicOnly(true);
    expect(JSON.parse(localStorage.getItem('auden:musiconly')!).state).toEqual({ preferMusicOnly: true });
  });
});

describe('wantsMusicOnly', () => {
  it('wants a YouTube row with no album while the setting is on', () => {
    expect(wantsMusicOnly(track('v'), plain)).toBe(true);
  });

  it('leaves alone a song, the setting off, a party, the DJ, a file on the device, clips and synthetic ids', () => {
    expect(wantsMusicOnly(song('s'), plain)).toBe(false);
    expect(wantsMusicOnly(track('v'), { ...plain, inParty: true })).toBe(false);
    expect(wantsMusicOnly(track('v'), { ...plain, dj: true })).toBe(false);
    expect(wantsMusicOnly(track('v'), { ...plain, playsLocally: true })).toBe(false);
    expect(wantsMusicOnly(track('auden:clip:1', { source: 'clip' }), plain)).toBe(false);
    expect(wantsMusicOnly(track('auden:local:1', { source: 'local' }), plain)).toBe(false);
    expect(wantsMusicOnly(track('auden:x'), plain)).toBe(false);
    expect(wantsMusicOnly(track('v', { unavailable: 'missing' }), plain)).toBe(false);
    useMusicOnly.setState({ preferMusicOnly: false });
    expect(wantsMusicOnly(track('v'), plain)).toBe(false);
  });

  it('believes a radio about which cut a row is, over the album guess', () => {
    noteCuts([
      { track: track('songNoAlbum'), isVideo: false },
      { track: song('videoWithAlbum'), isVideo: true },
    ]);
    expect(wantsMusicOnly(track('songNoAlbum'), plain)).toBe(false);
    expect(wantsMusicOnly(song('videoWithAlbum'), plain)).toBe(true);
  });

  it('stops asking about a video kept by hand or known to have no song', async () => {
    keep('kept');
    expect(wantsMusicOnly(track('kept'), plain)).toBe(false);
    audioVersionOf.mockResolvedValue(null);
    expect(await musicOnlyVersion(track('none'))).toBeNull();
    expect(wantsMusicOnly(track('none'), plain)).toBe(false);
  });
});

describe('musicOnlyVersion', () => {
  it('returns the song in the video’s place, keeping what the Spotify row stood for, and asks once', async () => {
    audioVersionOf.mockResolvedValue(song('s'));
    const video = track('v', { spotifyId: 'sp1' });
    const first = await musicOnlyVersion(video);
    expect(first).toMatchObject({ videoId: 's', title: 'Song', album: { name: 'Album' }, spotifyId: 'sp1' });
    expect(await musicOnlyVersion(video)).toEqual(first);
    expect(audioVersionOf).toHaveBeenCalledTimes(1);
  });

  it('shares one lookup between two waits', async () => {
    let answer!: (t: Track) => void;
    audioVersionOf.mockReturnValue(new Promise((resolve) => (answer = resolve)));
    const a = musicOnlyVersion(track('v'));
    const b = musicOnlyVersion(track('v'));
    answer(song('s'));
    expect((await a)?.videoId).toBe('s');
    expect((await b)?.videoId).toBe('s');
    expect(audioVersionOf).toHaveBeenCalledTimes(1);
  });

  it('takes the video’s own id back as no song', async () => {
    audioVersionOf.mockResolvedValue(track('v'));
    expect(await musicOnlyVersion(track('v'))).toBeNull();
  });

  it('settles a video whose answer is late, and asks again once it has left the playing and next rows', async () => {
    audioVersionOf.mockReturnValue(new Promise(() => {}));
    expect(await musicOnlyVersion(track('slow'), 20)).toBeNull();
    expect(wantsMusicOnly(track('slow'), plain)).toBe(false);
    forgetExcept(new Set(['slow']));
    expect(wantsMusicOnly(track('slow'), plain)).toBe(false);
    forgetExcept(new Set(['other']));
    expect(wantsMusicOnly(track('slow'), plain)).toBe(true);
  });

  it('settles a video whose lookup failed', async () => {
    audioVersionOf.mockRejectedValue(new Error('offline'));
    expect(await musicOnlyVersion(track('v'))).toBeNull();
    expect(wantsMusicOnly(track('v'), plain)).toBe(false);
  });

  it('lets a wait go, unsettled, when the setting changes, and forgets what was settled', async () => {
    audioVersionOf.mockReturnValue(new Promise(() => {}));
    expect(await musicOnlyVersion(track('slow'), 10)).toBeNull();
    const waiting = musicOnlyVersion(track('v'));
    await flush();
    useMusicOnly.getState().setPreferMusicOnly(false);
    expect(await waiting).toBeNull();
    useMusicOnly.getState().setPreferMusicOnly(true);
    expect(wantsMusicOnly(track('v'), plain)).toBe(true);
    expect(wantsMusicOnly(track('slow'), plain)).toBe(true);
  });
});

describe('asMusicOnlyOf', () => {
  it('is the song as YouTube Music names it, without a Spotify id the video never had', () => {
    const converted = asMusicOnlyOf({ ...song('s'), spotifyId: 'stray' }, track('v', { title: 'Song (Official Video)' }));
    expect(converted.videoId).toBe('s');
    expect(converted.title).toBe('Song');
    expect('spotifyId' in converted).toBe(false);
  });
});

describe('showsPicture', () => {
  it('shows no picture while the setting is on, except a video kept by hand or in a party', () => {
    expect(showsPicture('v', false)).toBe(false);
    expect(showsPicture('v', true)).toBe(true);
    keep('v');
    expect(showsPicture('v', false)).toBe(true);
    useMusicOnly.setState({ preferMusicOnly: false });
    expect(showsPicture('w', false)).toBe(true);
  });
});
