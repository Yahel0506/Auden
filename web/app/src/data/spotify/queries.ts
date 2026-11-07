import { useQuery } from '@tanstack/react-query';
import { useSyncExternalStore } from 'react';
import type { SpotifyTrack } from '@bitchord/shared/bridge';
import type { Track } from '@/data/music/types';
import { isTransientProblem, useSpotifyAuth, useSpotifySignedIn } from '@/extension/useSpotifyAuth';
import { fetchSpotifyAlbums, fetchSpotifyArtists, fetchSpotifyPlaylistInfo, fetchSpotifyPlaylists, fetchSpotifyTracks, pendingTrack } from './api';
import { accountKeyOf, cachedList, readLibraryCache, writeLibraryList, type LibraryKind } from './libraryCache';
import { onManualMatch, resolveSpotifyTrack } from './matches';

/** The account a request is made as: the one the extension read, else none. */
function useAccount(): string | null {
  const auth = useSpotifyAuth();
  if (auth.state !== 'ready') return null;
  // Opened cold during a throttle or offline: nothing says who is signed in, but the saved lists say whose they are.
  return accountKeyOf(auth.auth) ?? (isTransientProblem(auth.auth) ? (readLibraryCache()?.account ?? null) : null);
}

/**
 * A library list: the cached copy at once (and offline), refreshed when the account is signed in. The
 * cache is read whatever the auth says, so a passing problem never blanks a list already on screen.
 * No automatic retries: a Spotify call retries itself per page (retry.ts), and a retry here would
 * start the whole list over on top of that.
 */
function useLibraryList<T>(kind: LibraryKind, fetchAll: (signal?: AbortSignal) => Promise<T[]>, enabled = true) {
  const signedIn = useSpotifySignedIn();
  const account = useAccount();
  const cached = cachedList(account, kind) as T[] | undefined;
  return useQuery({
    queryKey: ['spotify', 'data', kind, account],
    queryFn: async ({ signal }) => {
      const list = await fetchAll(signal);
      if (account) writeLibraryList(account, kind, list as never);
      return list;
    },
    enabled: signedIn && enabled,
    staleTime: 5 * 60_000,
    retry: false,
    // Shown straight away; `initialDataUpdatedAt: 0` makes it stale, so it is refreshed behind.
    ...(cached && { initialData: cached, initialDataUpdatedAt: 0 }),
  });
}

export const useSpotifyPlaylists = () => useLibraryList('playlists', fetchSpotifyPlaylists);
export const useSpotifyAlbums = (enabled = true) => useLibraryList('albums', fetchSpotifyAlbums, enabled);
export const useSpotifyArtists = (enabled = true) => useLibraryList('artists', fetchSpotifyArtists, enabled);

export function useSpotifyPlaylistInfo(playlistId: string) {
  const signedIn = useSpotifySignedIn();
  return useQuery({
    queryKey: ['spotify', 'data', 'playlist', playlistId],
    queryFn: ({ signal }) => fetchSpotifyPlaylistInfo(playlistId, signal),
    enabled: signedIn && !!playlistId,
    staleTime: 5 * 60_000,
    retry: false,
  });
}

/** How many tracks are looked up on YouTube Music at once (MainViewModel.SPOTIFY_MATCH_PARALLELISM). */
const MATCH_PARALLELISM = 6;

export interface SpotifyRows {
  status: 'loading' | 'ready' | 'error';
  error: unknown;
  /** Spotify's rows, each replaced by its YouTube Music song as the matches arrive. */
  rows: Track[];
  total: number;
  /** How many rows still wait on a match. */
  matching: number;
  /** Reading the list stopped partway (a throttle, a dropped connection): what is shown is not all of it. */
  partial: boolean;
}

/**
 * One playlist's rows and their matches (MainViewModel.openSpotifyPage). The rows go up as soon as they
 * are read, marked as still matching, and are swapped in place as their YouTube Music songs are found.
 * Kept per account and playlist for the session, like the phone's hoisted page state, so going back to a
 * playlist doesn't look everything up again; matching pauses while nobody is looking and resumes where it
 * stopped. A match is remembered across sessions (matches.ts), and one the listener fixes by hand is
 * applied to the rows here and wins over any lookup still in flight.
 */
export class PlaylistJob {
  state: SpotifyRows = { status: 'loading', error: null, rows: [], total: 0, matching: 0, partial: false };
  private source: SpotifyTrack[] = [];
  /** Where the next page of the list starts (the extension's own count, which is not the number of rows). */
  private nextOffset = 0;
  private readonly listeners = new Set<() => void>();
  private fetching = false;
  private readonly inFlight = new Set<number>();
  /** Songs the listener picked by hand, by Spotify id: they take precedence over what a lookup brings back. */
  private readonly manual = new Map<string, Track>();
  private readonly stopManual = onManualMatch((spotifyId, track) => this.applyManual(spotifyId, track));

  constructor(private readonly playlistId: string) {}

  subscribe = (listener: () => void) => {
    this.listeners.add(listener);
    this.start();
    return () => void this.listeners.delete(listener);
  };

  private emit(change: Partial<SpotifyRows>) {
    const rows = change.rows ?? this.state.rows;
    this.state = { ...this.state, ...change, matching: rows.filter((r) => r.unavailable === 'matching').length };
    this.listeners.forEach((l) => l());
  }

  /** Reads the list again from where it stopped (or from the start, if nothing came). */
  retry() {
    if (this.state.status !== 'error' && !this.state.partial) return;
    this.emit({ status: this.source.length ? 'ready' : 'loading', error: null, partial: false });
    this.load();
  }

  /** The listener's own pick, for every row (here and in any other open playlist) that is this Spotify track. */
  private applyManual(spotifyId: string, track: Track) {
    this.manual.set(spotifyId, track);
    const rows = this.state.rows.map((row, i) => (this.source[i]?.id === spotifyId ? { ...track, spotifyId } : row));
    this.emit({ rows });
  }

  private start() {
    if (this.state.status === 'loading' && !this.fetching && !this.source.length) this.load();
    else this.pump();
  }

  private load() {
    if (this.fetching) return;
    this.fetching = true;
    fetchSpotifyTracks(
      this.playlistId,
      (soFar, total, nextOffset) => {
        this.source = soFar;
        this.nextOffset = nextOffset;
        // Rows already matched keep their match; new ones join as pending.
        const rows = soFar.map((t, i) => this.state.rows[i] ?? pendingTrack(t));
        this.emit({ rows, total });
        this.pump();
      },
      { from: this.nextOffset, soFar: this.source },
    )
      .then(() => this.emit({ status: 'ready', partial: false }))
      // Some rows came, then it stopped: say so, rather than let a throttled list pass for a short one.
      .catch((error: unknown) => this.emit(this.source.length ? { status: 'ready', error, partial: true } : { status: 'error', error }))
      .finally(() => (this.fetching = false));
  }

  /** Keeps up to MATCH_PARALLELISM lookups going, in order, while someone is looking. */
  private pump() {
    while (this.inFlight.size < MATCH_PARALLELISM && this.listeners.size > 0) {
      const index = this.state.rows.findIndex((r, i) => r.unavailable === 'matching' && !this.inFlight.has(i));
      if (index < 0) return;
      this.inFlight.add(index);
      const track = this.source[index]!;
      void resolveSpotifyTrack(track)
        .then((result) => {
          const picked = this.manual.get(track.id);
          // A pick made while the lookup ran wins; so does what the store says (it holds the pick).
          const found = picked ?? (result.status === 'found' ? result.track : null);
          const rows = [...this.state.rows];
          rows[index] = found ? { ...found, spotifyId: track.id } : pendingTrack(track, 'missing');
          this.emit({ rows });
        })
        .finally(() => {
          this.inFlight.delete(index);
          this.pump();
        });
    }
  }

  dispose() {
    this.stopManual();
  }
}

const jobs = new Map<string, PlaylistJob>();

/** Per account and playlist: another account's rows are never shown. */
export function useSpotifyRows(playlistId: string): SpotifyRows & { retry: () => void } {
  const account = useAccount();
  const key = `${account}|${playlistId}`;
  let job = jobs.get(key);
  if (!job) {
    job = new PlaylistJob(playlistId);
    jobs.set(key, job);
  }
  const current = job;
  const state = useSyncExternalStore(account ? current.subscribe : noSubscribe, () => current.state);
  return { ...state, retry: () => current.retry() };
}

const noSubscribe = () => () => undefined;
