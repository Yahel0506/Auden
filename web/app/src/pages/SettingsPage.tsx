import { ChevronRight, Users } from 'lucide-react';
import { lazy, Suspense, useState, type ComponentType, type ReactNode } from 'react';
import { MdOutlineExtension } from 'react-icons/md';
import { SiApplemusic, SiDiscord, SiSpotify, SiYoutubemusic } from 'react-icons/si';
import { PageHeader } from '@/components/PageHeader';
import { BackupSection } from '@/auden/backup/ui/BackupSection';
import { OfflineSection } from '@/auden/offline/ui/OfflineSection';
import { fsaSupported } from '@/auden/library/roots';
import { useAutoSyncSettings } from '@/auden/playlists/autoSync';
import { useDjSettings } from '@/auden/dj/settings';
import { useHomeSettings } from '@/auden/home/settings';
import { LOCAL_SHELF_KINDS, type LocalShelfKind } from '@/auden/home/shelves';
import { PrefetchLink } from '@/components/PrefetchLink';
import { useDiscord } from '@/extension/useDiscord';
import { useExtension } from '@/extension/useExtension';
import { useYtAuth } from '@/extension/useYtAuth';
import { useParty } from '@/party/useParty';
import { useHistorySettings } from '@/playback/historyReporting';
import { Select } from '@/components/Select';
import { useMusicOnly } from '@/playback/musicOnly';
import { useConnectSettings } from '@/auden/connect/store';
import { config } from '@/config';
import { PLAYER_THEMES, usePlayerLook, type PlayerTheme } from '@/shell/playerLook';
import { AUTOMIX_BARS } from '@/playback/automix/choices';
import { CROSSFADE_CHOICES, usePlayer } from '@/state/player';
import { AppleMusicIntegration, AppleMusicToggles, CanvasToggle, ClipNetworkToggle, DiscordIntegration, LyricSourcesAccess, SpotifyIntegration, Toggle } from './IntegrationSettings';
import { settingsUi } from './settingsStyles';

const LyricsSettings = lazy(() => import('@/shell/LyricsSettings').then((m) => ({ default: m.LyricsSettings })));

/** An integration's coloured glyph, as on the Extension page. */
function Glyph({ Icon, color, ink }: { Icon: ComponentType<{ size?: number }>; color: string; ink?: string }) {
  return (
    <span className={settingsUi.integrationIcon} style={{ backgroundColor: color, ...(ink && { color: ink }) }}>
      <Icon size={15} />
    </span>
  );
}

const THEME_LABELS: Record<PlayerTheme, string> = { continuation: 'Continuation', colors: 'Colors', blur: 'Blur' };
const THEME_DETAIL: Record<PlayerTheme, string> = {
  continuation: 'A soft mesh that carries on the colors of the cover, blurrier and darker toward the bottom.',
  colors: 'The classic drifting mesh gradient.',
  blur: 'The cover, blurred across the whole screen.',
};

const SHELF_LABELS: Record<LocalShelfKind, string> = {
  LOOP: 'On repeat',
  REDISCOVER: 'Rediscover',
  RECENTLY_ADDED: 'Recently added',
  PLAYLISTS: 'Your playlists',
  ARTIST_MIX: 'Artist mix',
  LIBRARY_SHUFFLE: 'From your library',
};

/** A row that opens a page of its own. */
function LinkRow({ to, icon, title, value }: { to: string; icon?: ReactNode; title: string; value?: string | null }) {
  return (
    <PrefetchLink to={to} className={`${icon ? settingsUi.integration : settingsUi.integrationPlain} ${settingsUi.linkRow}`}>
      {icon}
      <span className={settingsUi.integrationName}>{title}</span>
      <span className={settingsUi.value}>
        {value}
        <ChevronRight size={16} />
      </span>
    </PrefetchLink>
  );
}

function youtubeText(auth: ReturnType<typeof useYtAuth>): string {
  if (auth.state === 'checking') return 'Checking…';
  if (auth.state === 'unavailable') return 'Needs the extension';
  const { browserSignedIn, connected, active } = auth.auth;
  if (!browserSignedIn) return 'Not signed in';
  if (!connected) return 'Disconnected';
  return active ? active.name : 'Signed in';
}

/**
 * Settings (SettingsSheet on the phone): the account and its integrations,
 * Listen Together, playback and lyrics, in the same grouped rows.
 */
export default function SettingsPage() {
  const extension = useExtension();
  const autoSync = useAutoSyncSettings();
  const home = useHomeSettings();
  const look = usePlayerLook();
  const musicOnly = useMusicOnly((s) => s.preferMusicOnly);
  const dj = useDjSettings();
  const yt = useYtAuth();
  const discord = useDiscord();
  const party = useParty();
  const autoplay = usePlayer((s) => s.autoplay);
  const crossfadeSec = usePlayer((s) => s.crossfadeSec);
  const automix = usePlayer((s) => s.automix);
  const automixBars = usePlayer((s) => s.automixBars);
  const showNerdStats = usePlayer((s) => s.showNerdStats);
  const shareHistory = useHistorySettings((s) => s.share);
  const connectEnabled = useConnectSettings((s) => s.enabled);
  const setConnectEnabled = useConnectSettings((s) => s.setEnabled);
  const setShareHistory = useHistorySettings((s) => s.setShare);
  const { setAutoplay, setCrossfade, setAutomix, setAutomixBars, setShowNerdStats } = usePlayer.getState();
  const [lyrics, setLyrics] = useState(false);
  const present = extension.state === 'present';
  const discordText =
    discord.state === 'ready' && discord.discord.connected
      ? discord.discord.enabled
        ? 'On'
        : 'Off'
      : discord.state === 'ready' && discord.discord.available
        ? 'Not connected'
        : null;

  return (
    <div className={settingsUi.centered}>
      <PageHeader title="Settings" />
      <div className={settingsUi.body}>
        <h2 className={settingsUi.sectionHeader}>Account & Integrations</h2>
        <section className={settingsUi.group}>
          <div className={settingsUi.integration}>
            <Glyph Icon={SiYoutubemusic} color="#ffffff" ink="#ff0033" />
            <span className={settingsUi.integrationName}>YouTube Music</span>
            <span className={settingsUi.value}>{youtubeText(yt)}</span>
          </div>
          <div className={settingsUi.integration}>
            <Glyph Icon={SiSpotify} color="#1ed760" />
            <span className={settingsUi.integrationName}>Spotify</span>
            {present ? <SpotifyIntegration /> : <span className={settingsUi.value}>Needs the extension</span>}
          </div>
          {present && discord.state === 'ready' && discord.discord.available && (
            <LinkRow to="/settings/discord" icon={<Glyph Icon={SiDiscord} color="#5865f2" />} title="Discord" value={discordText} />
          )}
          <LinkRow
            to="/extension"
            icon={<Glyph Icon={MdOutlineExtension} color="#636366" />}
            title="Auden Extension"
            value={present ? `v${extension.extension.info.version}` : 'Not installed'}
          />
        </section>
        {config.connectUrl && (
          <section className={`${settingsUi.group} mt-3`}>
            <Toggle
              label="Play on my other devices"
              detail="Shows your other devices with Auden open on this account in Audio output, so you can hand playback to them and control it from here."
              checked={connectEnabled}
              onChange={setConnectEnabled}
            />
          </section>
        )}
        <section className={`${settingsUi.group} mt-3 empty:hidden`}>
          <Toggle
            label="Share Listening Activity to YouTube"
            detail={
              present && !extension.extension.info.capabilities.includes('yt.stats')
                ? 'Update the Auden extension to share: this version can’t send plays to YouTube.'
                : 'Songs you play here go to your YouTube Music history and shape its recommendations.'
            }
            checked={shareHistory}
            onChange={setShareHistory}
          />
          <CanvasToggle />
        </section>

        <h2 className={settingsUi.sectionHeader}>Apple Music</h2>
        <section className={settingsUi.group}>
          <div className={settingsUi.integration}>
            <Glyph Icon={SiApplemusic} color="#fa2d48" />
            <span className={settingsUi.integrationName}>Extension Access</span>
            {present ? <AppleMusicIntegration /> : <span className={settingsUi.value}>Needs the extension</span>}
          </div>
          <AppleMusicToggles />
          <ClipNetworkToggle />
        </section>
        <p className={settingsUi.footer}>From Apple Music’s public pages. No Apple Music account or sign-in needed.</p>

        <h2 className={settingsUi.sectionHeader}>Home</h2>
        <section className={settingsUi.group}>
          <div className={settingsUi.integration}>
            <span className={settingsUi.integrationName}>Your music on Home</span>
            <span className={settingsUi.value}>{home.localWeight === 0 ? 'Feed only' : `${home.localWeight}%`}</span>
          </div>
          <div className="px-4 pb-3">
            <input
              type="range"
              min={0}
              max={100}
              step={5}
              value={home.localWeight}
              onChange={(e) => home.setLocalWeight(Number(e.target.value))}
              aria-label="How much of Home is your own music"
              className="w-full accent-[var(--keyColor,currentColor)]"
            />
            <div className="flex justify-between type-callout text-secondary">
              <span>YouTube Music</span>
              <span>Yours</span>
            </div>
          </div>
          {LOCAL_SHELF_KINDS.map((kind) => (
            <Toggle key={kind} label={SHELF_LABELS[kind]} checked={home.enabled[kind]} onChange={(on) => home.setShelf(kind, on)} />
          ))}
          <Toggle label="Hide Recently Played" detail="Takes the shelf off Home, YouTube’s own copy too." checked={home.hideRecents} onChange={home.setHideRecents} />
          <Toggle label="Hide music videos" detail="Takes them off Home’s feed, with any shelf left with nothing else on it." checked={home.hideVideos} onChange={home.setHideVideos} />
          <Toggle label="Square video thumbnails" detail="Music videos show as squares, like songs." checked={home.squareVideoThumbnails} onChange={home.setSquareVideoThumbnails} />
        </section>
        <section className={`${settingsUi.group} mt-3`}>
          <label className={settingsUi.controlRow}>
            <span className={settingsUi.rowText}>
              <span>Player background</span>
              <span className={settingsUi.rowDetail}>{THEME_DETAIL[look.theme]}</span>
            </span>
            <Select<PlayerTheme>
              label="Player background"
              value={look.theme}
              options={PLAYER_THEMES.map((t) => ({ value: t, label: THEME_LABELS[t] }))}
              onChange={look.setTheme}
            />
          </label>
          <Toggle
            label="Full-screen covers"
            detail="Album and playlist covers run to the edges of the page and dissolve into it. On narrow windows only."
            checked={look.fullBleedCovers}
            onChange={look.setFullBleedCovers}
          />
        </section>
        <p className={settingsUi.footer}>Built from what you play and the music on this device. With no network or no extension, Home is only yours.</p>

        <h2 className={settingsUi.sectionHeader}>Auden DJ</h2>
        <section className={settingsUi.group}>
          <Toggle label="Voice" detail="The DJ speaks between sets. Off, or when the voice isn’t available, you read what it says." checked={dj.voice} onChange={(voice) => dj.set({ voice })} />
          <div className={settingsUi.integration}>
            <span className={settingsUi.integrationName}>Language</span>
            <select className="rounded-md bg-quinary px-2 py-1 type-callout text-primary" value={dj.language} onChange={(e) => dj.set({ language: e.target.value === 'es' ? 'es' : 'en' })} aria-label="DJ language">
              <option value="en">English</option>
              <option value="es">Español</option>
            </select>
          </div>
          <div className={settingsUi.integration}>
            <span className={settingsUi.integrationName}>Songs per set</span>
            <span className={settingsUi.value}>{dj.blockSize}</span>
          </div>
          <div className="px-4 pb-3">
            <input type="range" min={5} max={12} step={1} value={dj.blockSize} onChange={(e) => dj.set({ blockSize: Number(e.target.value) })} aria-label="Songs per DJ set" className="w-full" />
          </div>
        </section>
        <p className={settingsUi.footer}>Start the DJ from the card on Home. It plays your own music; it can’t run in a Listen Together party.</p>

        <h2 className={settingsUi.sectionHeader}>Local Library</h2>
        <section className={settingsUi.group}>
          <LinkRow to="/local" title="On This Device" value="Folders & songs" />
          <Toggle
            label="Keep saved playlists up to date"
            detail="Adds new songs from YouTube Music and Spotify to your saved copies. Nothing is ever changed on those services."
            checked={autoSync.enabled}
            onChange={autoSync.setEnabled}
          />
          <Toggle
            label="Also when data saver is on"
            detail="For saved copies of YouTube Music and Spotify playlists. Browsers only report Save-Data or a cellular link on Chromium; elsewhere this has no effect. Offline copies have their own rule: never on Save-Data or cellular."
            checked={autoSync.alsoOnDataSaver}
            onChange={autoSync.setAlsoOnDataSaver}
          />
        </section>
        <p className={settingsUi.footer}>
          {fsaSupported()
            ? 'Your browser remembers the folders you add, but asks for permission again each visit: tap “Reconnect folder” on the On This Device page. Songs never leave this device.'
            : 'This browser can’t keep access to a folder between visits. Auden remembers the songs it indexed; pick the same folder again each visit (Reconnect folder) to play them. Chromium-based browsers remember folders.'}
        </p>

        <h2 className={settingsUi.sectionHeader}>Listen Together</h2>
        <section className={settingsUi.group}>
          <LinkRow
            to="/settings/listen-together"
            icon={<Glyph Icon={Users} color="#fa2d48" />}
            title="Listen Together"
            value={party.code ? `In party ${party.code}` : 'Listen in sync with friends'}
          />
        </section>

        <h2 className={settingsUi.sectionHeader}>Playback</h2>
        <section className={settingsUi.group}>
          <Toggle
            label="AutoPlay"
            detail="When the queue ends, keep playing similar music."
            checked={autoplay}
            onChange={setAutoplay}
          />
          <label className={settingsUi.controlRow}>
            <span>Crossfade</span>
            <Select<number | 'automix'>
              label="Crossfade"
              value={automix ? 'automix' : crossfadeSec}
              options={[
                ...CROSSFADE_CHOICES.map((seconds) => ({ value: seconds, label: seconds === 0 ? 'Off' : `${seconds} seconds` })),
                { value: 'automix', label: 'Automix' },
              ]}
              onChange={(value) => (value === 'automix' ? setAutomix(true) : setCrossfade(value))}
            />
          </label>
          {automix && (
            <label className={settingsUi.controlRow}>
              <span>Blend Length</span>
              <Select
                label="Blend Length"
                value={automixBars}
                options={AUTOMIX_BARS.map((bars) => ({ value: bars, label: `Up to ${bars} bars` }))}
                onChange={setAutomixBars}
              />
            </label>
          )}
          <Toggle
            label="Prefer music-only version"
            detail="Music videos play as their song when YouTube Music has one, and the next track is looked up ahead so Automix and crossfade keep working. Otherwise the video’s own audio plays, without the picture."
            checked={musicOnly}
            onChange={(on) => useMusicOnly.getState().setPreferMusicOnly(on)}
          />
          <Toggle
            label="Show Stats for Nerds"
            detail="Codec, bitrate and sample rate under the artist in the full-screen player, and what Automix is doing."
            checked={showNerdStats}
            onChange={setShowNerdStats}
          />
        </section>
        {automix && (
          <p className={settingsUi.footer}>
            Automix finds each song’s tempo, key and phrases, then blends on the beat: the next song is time-stretched to match, and
            the bass swaps over halfway. Where it can, it loops a few instrumental bars of each song and ramps the tempo between
            them. Songs it can’t read in time play to the end and crossfade. Based on AutoMix by David Finnegan (AGPL-3.0).
          </p>
        )}

        <h2 className={settingsUi.sectionHeader}>Lyrics</h2>
        <section className={settingsUi.group}>
          <button className={`${settingsUi.integrationPlain} ${settingsUi.linkRow}`} onClick={() => setLyrics(true)}>
            <span className={settingsUi.integrationName}>Lyric Sources</span>
            <span className={settingsUi.value}>
              Order, sources, syllable timing
              <ChevronRight size={16} />
            </span>
          </button>
          <div className={settingsUi.integrationPlain}>
            <span className={settingsUi.integrationName}>Lyric Sources Access</span>
            {present ? <LyricSourcesAccess /> : <span className={settingsUi.value}>Needs the extension</span>}
          </div>
        </section>

        <OfflineSection />

        <BackupSection />
      </div>
      {lyrics && (
        <Suspense fallback={null}>
          <LyricsSettings onClose={() => setLyrics(false)} />
        </Suspense>
      )}
    </div>
  );
}
