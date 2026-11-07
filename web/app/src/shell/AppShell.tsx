import { lazy, Suspense, useEffect } from 'react';
import { resumeSession } from '@/party/listenTogether';
import { startPartySync } from '@/party/partySync';
import { useDiscordPresence } from '@/playback/discordPresence';
import { startHistoryReporting } from '@/playback/historyReporting';
import { startAudenQuerySync } from '@/auden/db';
import { installLocalLibrary } from '@/auden/library/localResolver';
import { startAutoSync } from '@/auden/playlists/autoSync';
import { DbReloadBanner } from './DbReloadBanner';
import { startPlayRecorder } from '@/auden/stats/recorder';
import { JobBanner } from '@/auden/jobs/JobBanner';
import { SavedLyricsHost } from './LyricsManage';
import { SpotifyMatchHost } from './SpotifyMatchDialog';
import { startLivePresence } from '@/playback/livePresence';
import { Outlet } from 'react-router';
import { PrefetchLink } from '@/components/PrefetchLink';
import { config } from '@/config';
import { useExtension } from '@/extension/useExtension';
import { isMobileBrowser } from '@/lib/platform';
import { useUi } from '@/state/ui';
import { useKeyboardShortcuts } from './useKeyboardShortcuts';
import { Banner, bannerButtonClass } from './Banner';
import { MobileTopBar } from './MobileTopBar';
import { useConnectSession } from '@/auden/connect/useConnectSession';
import { startLastSession } from '@/state/lastSession';
import { PlayerBar } from './PlayerBar';
import { SidePanel } from './SidePanel';
import { Sidebar } from './Sidebar';

const mobile = isMobileBrowser();

// It carries am-lyrics; loaded the first time it opens, as the side panel's lyrics are.
const FullScreenPlayer = lazy(() => import('./FullScreenPlayer').then((m) => ({ default: m.FullScreenPlayer })));

/**
 * The app frame (report §1): glass sidebar, the scrolling page, the floating
 * player pill, the side panel, and the bottom band when there is one.
 */
export function AppShell() {
  useKeyboardShortcuts();
  useDiscordPresence();
  // Auden Connect: this browser in the account's device list; while another device plays, the player below mirrors it.
  useConnectSession();
  // What was playing comes back after a reload, paused where it was.
  useEffect(() => startLastSession(), []);
  // Listen Together: pick a party back up after a reload, and keep the player in step with it.
  useEffect(() => {
    resumeSession();
    return startPartySync();
  }, []);
  // Plays into the account's YouTube Music history (Settings › Share Listening Activity).
  useEffect(() => startHistoryReporting(), []);
  // Auden's own library: listening kept on this device (Home and the DJ read it), and queries over it kept fresh.
  useEffect(() => startPlayRecorder(), []);
  useEffect(() => startAudenQuerySync(), []);
  // Local files: tells the player which songs have a file on this device, and keeps that current.
  useEffect(() => installLocalLibrary(), []);
  // Offline copies: registered with the player (so kept songs play), and the queue's runner. Loaded after first paint; the byte source
  // (YouTube.js, BotGuard) is a further lazy import inside it.
  useEffect(() => {
    let off = () => {};
    let gone = false;
    void import('@/auden/offline/runtime').then((m) => {
      if (!gone) off = m.installOffline();
    });
    return () => {
      gone = true;
      off();
    };
  }, []);
  // Saved copies of YouTube Music / Spotify playlists gain their new songs (at most every 6 h, when the tab is in front).
  useEffect(() => startAutoSync(), []);
  // Counts this tab in the "listening now" figure beside the phone and PC apps.
  useEffect(() => startLivePresence(), []);
  const extension = useExtension();
  const bannerDismissed = useUi((s) => s.installBannerDismissed);
  const dismissBanner = useUi((s) => s.dismissInstallBanner);
  const fullScreen = useUi((s) => s.fullScreen);

  let banner = null;
  if (mobile) {
    banner = (
      <Banner
        title="Auden is better in the app."
        detail="Phone browsers can’t run the Auden extension. The Android app has everything."
        action={
          <a className={bannerButtonClass} href={config.androidAppUrl} target="_blank" rel="noreferrer">
            Get the App
          </a>
        }
      />
    );
  } else if (extension.state === 'missing' && !bannerDismissed) {
    banner = (
      <Banner
        title="Play YouTube Music with the Auden extension."
        detail="Playback, YouTube Music sign-in, Spotify and Discord need the extension. Addons and lyrics work without it."
        action={
          <PrefetchLink className={bannerButtonClass} to="/extension">
            Get the Extension
          </PrefetchLink>
        }
        onDismiss={dismissBanner}
      />
    );
  }

  return (
    <div className="grid h-full grid-rows-[minmax(0,1fr)_auto]">
      {/* The side panel slides in from beyond the right edge; never let it scroll the page. */}
      <div className="relative min-h-0 overflow-hidden">
        <Sidebar />
        <MobileTopBar />
        {/* The page scrolls under the fixed sidebar and the floating player. */}
        <main
          id="scrollable-page"
          className="absolute inset-0 overflow-x-hidden overflow-y-auto pl-(--web-navigation-width) [scrollbar-gutter:stable] data-tint:bg-(--page-tint) data-tint:transition-[background-color] data-tint:duration-300 data-tint:ease-in max-sm:pt-(--mobile-top-bar-height) max-sm:pl-0"
        >
          {/* Clears the floating pill: its height, its offset, and some air. */}
          <div className="min-h-full pb-[calc(var(--player-pill-height)+var(--player-bottom-offset)+40px)]">
            <Outlet />
          </div>
        </main>
        <PlayerBar />
        <SidePanel />
      </div>
      <DbReloadBanner />
      <div>
        <JobBanner />
        <SavedLyricsHost />
        <SpotifyMatchHost />
        {banner}
      </div>
      {fullScreen && (
        <Suspense fallback={null}>
          <FullScreenPlayer />
        </Suspense>
      )}
    </div>
  );
}
