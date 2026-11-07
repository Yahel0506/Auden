// @vitest-environment happy-dom
import type { SpotifyTrack } from '@bitchord/shared/bridge';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import type { Track } from '@/data/music/types';
import type { MatchResult } from './matches';

const sp = (i: number): SpotifyTrack => ({ id: `sp${i}`, title: `Song ${i}`, artist: 'A', album: null, durationMs: 200_000, imageUrl: null });
const yt = (videoId: string): Track => ({ videoId, title: videoId, artists: [{ name: 'A', id: null }], album: null, durationSec: 200, explicit: false, thumbnail: null, index: null, plays: null });

const fetchTracks = vi.hoisted(() => vi.fn());
const resolve = vi.hoisted(() => vi.fn());
const manual = vi.hoisted(() => ({ listener: null as null | ((id: string, t: Track) => void) }));

vi.mock('./api', async (original) => ({ ...(await original<typeof import('./api')>()), fetchSpotifyTracks: fetchTracks }));
vi.mock('./matches', () => ({
  resolveSpotifyTrack: resolve,
  onManualMatch: (l: (id: string, t: Track) => void) => {
    manual.listener = l;
    return () => (manual.listener = null);
  },
}));
vi.mock('@/extension/useSpotifyAuth', () => ({ useSpotifyAuth: () => ({ state: 'checking' }), useSpotifySignedIn: () => false }));

const { PlaylistJob } = await import('./queries');

const loaded = (tracks: SpotifyTrack[]) =>
  fetchTracks.mockImplementation(async (_id: string, onPage: (s: SpotifyTrack[], total: number, next: number) => void) => {
    onPage(tracks, tracks.length, tracks.length);
    return tracks;
  });
const settle = () => new Promise((r) => setTimeout(r, 20));
const found = (videoId: string): MatchResult => ({ status: 'found', track: yt(videoId), manual: false });

beforeEach(() => {
  fetchTracks.mockReset();
  resolve.mockReset();
  manual.listener = null;
});

describe('PlaylistJob', () => {
  it('swaps each row for its match, keeping the Spotify id, and leaves an error as not found', async () => {
    loaded([sp(1), sp(2)]);
    resolve.mockImplementation(async (t: SpotifyTrack): Promise<MatchResult> => (t.id === 'sp1' ? found('yt1') : { status: 'error', error: new Error('offline') }));
    const job = new PlaylistJob('pl');
    job.subscribe(() => {});
    await settle();
    expect(job.state.rows[0]).toMatchObject({ videoId: 'yt1', spotifyId: 'sp1' });
    expect(job.state.rows[0]!.unavailable).toBeUndefined();
    expect(job.state.rows[1]).toMatchObject({ unavailable: 'missing', spotifyId: 'sp2' });
  });

  it('applies a hand-picked match to the row at once', async () => {
    loaded([sp(1), sp(2)]);
    resolve.mockImplementation(async (t: SpotifyTrack): Promise<MatchResult> => (t.id === 'sp1' ? found('yt1') : { status: 'missing' }));
    const job = new PlaylistJob('pl');
    job.subscribe(() => {});
    await settle();
    manual.listener!('sp2', yt('picked'));
    expect(job.state.rows[1]).toMatchObject({ videoId: 'picked', spotifyId: 'sp2' });
    expect(job.state.rows[1]!.unavailable).toBeUndefined();
    expect(job.state.matching).toBe(0);
  });

  it('a pick made while that row\'s lookup is still in flight wins over what the lookup brings back', async () => {
    loaded([sp(1)]);
    let release!: (r: MatchResult) => void;
    resolve.mockImplementation(() => new Promise<MatchResult>((r) => (release = r)));
    const job = new PlaylistJob('pl');
    job.subscribe(() => {});
    await settle();
    manual.listener!('sp1', yt('picked'));
    release(found('autoHit'));
    await settle();
    expect(job.state.rows[0]).toMatchObject({ videoId: 'picked', spotifyId: 'sp1' });
  });

  it('says so when the list stopped partway, and Load the rest asks from where it stopped', async () => {
    let call = 0;
    fetchTracks.mockImplementation(async (_id: string, onPage: (s: SpotifyTrack[], total: number, next: number) => void, opts: { from?: number; soFar?: SpotifyTrack[] }) => {
      call++;
      if (call === 1) {
        onPage([sp(1), sp(2)], 5, 2);
        throw new Error('throttled');
      }
      expect(opts).toMatchObject({ from: 2 });
      expect(opts.soFar).toHaveLength(2);
      const all = [sp(1), sp(2), sp(3), sp(4), sp(5)];
      onPage(all, 5, 5);
      return all;
    });
    resolve.mockResolvedValue({ status: 'missing' });
    const job = new PlaylistJob('pl');
    job.subscribe(() => {});
    await settle();
    expect(job.state).toMatchObject({ status: 'ready', partial: true });
    expect(job.state.rows).toHaveLength(2);
    job.retry();
    await settle();
    expect(job.state).toMatchObject({ status: 'ready', partial: false });
    expect(job.state.rows).toHaveLength(5);
  });

  it('shows an error only when nothing at all was read', async () => {
    fetchTracks.mockRejectedValue(new Error('signed out'));
    const job = new PlaylistJob('pl');
    job.subscribe(() => {});
    await settle();
    expect(job.state).toMatchObject({ status: 'error', partial: false });
  });
});
