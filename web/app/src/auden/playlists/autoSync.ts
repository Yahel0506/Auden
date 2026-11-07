import { create } from 'zustand';
import { persist } from 'zustand/middleware';
import { queryClient } from '@/lib/queryClient';
import { spotifyAuthQueryKey } from '@/extension/useSpotifyAuth';
import { metered } from '@/data/spotify/canvas';
import { useJobs } from '../jobs/jobs';
import { cloudDeps } from './cloudDeps';
import { type CloudDeps, isSpotifySource, resaveCopy } from './cloudSaver';
import { refreshCoverFor } from './covers';
import { startManualMatchSync } from './manualMatch';
import { listPlaylists } from './store';

/**
 * Keeps saved copies of YouTube Music and Spotify playlists and albums up to date (Android PlaylistAutoSync): when the tab
 * comes to the front, at most every six hours per copy, new songs are added. It never writes to either service. Only a
 * Spotify copy ever loses songs (those the original dropped; see reconcile.ts). One summary, and only if songs arrived.
 */

export const MIN_INTERVAL_MS = 6 * 60 * 60 * 1000;
/** A copy that failed is tried again after this, not on every tab switch. */
export const FAILURE_BACKOFF_MS = 60 * 60 * 1000;
/** After the tab comes forward, wait for the page to settle (and for the extension to answer) first. */
export const SETTLE_MS = 15_000;
const BETWEEN_COPIES_MS = 1_500;

interface AutoSyncSettings {
  enabled: boolean;
  /** Also sync while the browser says Save-Data / a cellular link (Android: "Also on mobile data"). */
  alsoOnDataSaver: boolean;
  setEnabled: (on: boolean) => void;
  setAlsoOnDataSaver: (on: boolean) => void;
}

export const useAutoSyncSettings = create<AutoSyncSettings>()(
  persist(
    (set) => ({
      enabled: true,
      alsoOnDataSaver: false,
      setEnabled: (enabled) => set({ enabled }),
      setAlsoOnDataSaver: (alsoOnDataSaver) => set({ alsoOnDataSaver }),
    }),
    { name: 'auden:autosync', version: 1, partialize: (s) => ({ enabled: s.enabled, alsoOnDataSaver: s.alsoOnDataSaver }) },
  ),
);

/** When each copy was last tried, and whether it worked. */
export interface Stamps {
  get(sourceId: string): { at: number; ok: boolean } | undefined;
  set(sourceId: string, value: { at: number; ok: boolean }): void;
}

const STAMP_KEY = 'auden:autosync:stamps';
export const localStamps: Stamps = {
  get(sourceId) {
    try {
      return (JSON.parse(localStorage.getItem(STAMP_KEY) ?? '{}') as Record<string, { at: number; ok: boolean }>)[sourceId];
    } catch {
      return undefined;
    }
  },
  set(sourceId, value) {
    try {
      const all = JSON.parse(localStorage.getItem(STAMP_KEY) ?? '{}') as Record<string, unknown>;
      localStorage.setItem(STAMP_KEY, JSON.stringify({ ...all, [sourceId]: value }));
    } catch {
      // private mode: the sync just runs more often
    }
  },
};

export interface SyncEnv {
  now: () => number;
  settings: () => { enabled: boolean; alsoOnDataSaver: boolean };
  dataSaver: () => boolean;
  spotifySignedIn: () => boolean;
  copies: () => Promise<Array<{ sourceId: string; title: string; playlistId: string }>>;
  deps: CloudDeps<{ id: string }>;
  stamps: Stamps;
  pause: (ms: number) => Promise<void>;
  /** Stops the run (the tab went away). */
  cancelled?: () => boolean;
  /**
   * The offline copies' pass (keepUpdated.ts): its own switches and gates, not the cloud copies', and its own stamps.
   * Absent in tests of the cloud pass.
   */
  offline?: (env: SyncEnv) => Promise<OfflineReport | null>;
}

export interface OfflineReport {
  queued: number;
  asking: number;
}

/** Whether the offline pass may run now: copies on, "Keep playlists updated" on, and a link that is not metered. */
export function offlinePassAllowed(s: { enabled: boolean; keepUpdated: boolean }, dataSaver: boolean): boolean {
  return s.enabled && s.keepUpdated && !dataSaver;
}

const OFFLINE_STAMP = 'offline:';

export interface SyncReport {
  skipped: 'off' | 'data-saver' | null;
  tried: number;
  added: number;
  removed: number;
}

export const isDue = (stamp: { at: number; ok: boolean } | undefined, now: number): boolean =>
  !stamp || now - stamp.at >= (stamp.ok ? MIN_INTERVAL_MS : FAILURE_BACKOFF_MS);

/** One pass over the copies that are due. Pure apart from what [env] gives it. */
export async function runDue(env: SyncEnv): Promise<SyncReport> {
  const report: SyncReport = { skipped: null, tried: 0, added: 0, removed: 0 };
  const settings = env.settings();
  if (!settings.enabled) return { ...report, skipped: 'off' };
  if (env.dataSaver() && !settings.alsoOnDataSaver) return { ...report, skipped: 'data-saver' };
  const now = env.now();
  for (const copy of await env.copies()) {
    if (env.cancelled?.()) break;
    if (!isDue(env.stamps.get(copy.sourceId), now)) continue;
    // A Spotify copy needs a Spotify session; without one it is skipped, not stamped, so it is tried at the next chance.
    if (isSpotifySource(copy.sourceId) && !env.spotifySignedIn()) continue;
    report.tried++;
    const outcome = await resaveCopy(env.deps, copy.sourceId, copy.title);
    if ('failed' in outcome) {
      env.stamps.set(copy.sourceId, { at: env.now(), ok: false });
    } else {
      env.stamps.set(copy.sourceId, { at: env.now(), ok: true });
      report.added += outcome.added;
      report.removed += outcome.removed;
      if (outcome.added || outcome.removed) void refreshCoverFor(copy.playlistId);
    }
    await env.pause(BETWEEN_COPIES_MS);
  }
  return report;
}

export const SUMMARY_JOB = 'autosync';
export const summaryText = (added: number): string => `${added} new ${added === 1 ? 'song' : 'songs'} added to your saved playlists`;

/** The banner line, only if songs arrived (removals are not announced, as on the phone). */
export function announce(report: SyncReport): void {
  if (report.added <= 0) return;
  useJobs.getState().start({ id: SUMMARY_JOB, title: 'Playlists updated', total: 1 });
  useJobs.getState().finish(SUMMARY_JOB, summaryText(report.added));
}

export const OFFLINE_JOB = 'autosync-offline';
export function offlineSummary(r: OfflineReport): string | null {
  const parts = [
    r.queued > 0 ? `${r.queued} new ${r.queued === 1 ? 'song' : 'songs'} queued for offline` : '',
    r.asking > 0 ? `${r.asking} ${r.asking === 1 ? 'playlist is' : 'playlists are'} waiting for your OK on the Offline page` : '',
  ].filter(Boolean);
  return parts.length ? parts.join('; ') : null;
}
function announceOffline(report: OfflineReport): void {
  const text = offlineSummary(report);
  if (!text) return;
  useJobs.getState().start({ id: OFFLINE_JOB, title: 'Offline copies updated', total: 1 });
  useJobs.getState().finish(OFFLINE_JOB, text);
}

/** The live offline pass: loaded only when the switches are on (the offline code is a chunk of its own). */
async function liveOffline(env: SyncEnv): Promise<OfflineReport | null> {
  const { useOfflineSettings } = await import('@/auden/offline/settings');
  if (!offlinePassAllowed(useOfflineSettings.getState(), env.dataSaver())) return null;
  const { getOfflineRuntime } = await import('@/auden/offline/runtime');
  if (!getOfflineRuntime().supported) return null;
  const { syncAllLive } = await import('@/auden/offline/keepUpdatedLive');
  const now = env.now();
  const report = await syncAllLive({
    isDue: (key) => isDue(env.stamps.get(OFFLINE_STAMP + key), now),
    stamp: (key, ok) => env.stamps.set(OFFLINE_STAMP + key, { at: env.now(), ok }),
    cancelled: env.cancelled,
    pause: () => env.pause(BETWEEN_COPIES_MS),
  });
  return report;
}

const liveEnv = (cancelled: () => boolean): SyncEnv => ({
  now: Date.now,
  settings: () => useAutoSyncSettings.getState(),
  dataSaver: metered,
  spotifySignedIn: () => !!(queryClient.getQueryData(spotifyAuthQueryKey) as { signedIn?: boolean } | undefined)?.signedIn,
  copies: async () => (await listPlaylists()).filter((s) => s.playlist.sourceId).map((s) => ({ sourceId: s.playlist.sourceId!, title: s.playlist.title, playlistId: s.playlist.id })),
  deps: cloudDeps as unknown as CloudDeps<{ id: string }>,
  stamps: localStamps,
  pause: (ms) => new Promise((r) => setTimeout(r, ms)),
  cancelled,
  offline: liveOffline,
});

/** One pass under a lock shared by every tab, so two tabs never sync at once. */
export async function runOnce(env: SyncEnv): Promise<SyncReport | null> {
  const locks = (typeof navigator !== 'undefined' ? navigator.locks : undefined) as LockManager | undefined;
  const body = async () => {
    const report = await runDue(env);
    announce(report);
    // After the cloud copies, so a copy that just gained songs is read with them. Its own gates; a failure here never hides the report above.
    const offline = await env.offline?.(env).catch(() => null);
    if (offline) announceOffline(offline);
    return report;
  };
  if (!locks) return body();
  return locks.request('auden-autosync', { ifAvailable: true }, async (lock) => (lock ? body() : null));
}

/**
 * Starts watching the tab: each time it becomes visible, wait [SETTLE_MS] (and drop the run if it is hidden again), then
 * sync what is due. Returns the cleanup. Mount once.
 */
export function startAutoSync(settleMs: number = SETTLE_MS): () => void {
  let timer: ReturnType<typeof setTimeout> | undefined;
  let token = 0;
  const arm = () => {
    clearTimeout(timer);
    token++;
    if (document.visibilityState !== 'visible') return;
    const mine = token;
    timer = setTimeout(() => void runOnce(liveEnv(() => token !== mine || document.visibilityState !== 'visible')), settleMs);
  };
  document.addEventListener('visibilitychange', arm);
  arm();
  // A match the listener fixes by hand also fixes the saved copies.
  const stopMatches = startManualMatchSync();
  return () => {
    stopMatches();
    document.removeEventListener('visibilitychange', arm);
    clearTimeout(timer);
    token++;
  };
}
