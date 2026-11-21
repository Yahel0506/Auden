import { t as translateUi } from '@/i18n';
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

const THEME_LABELS: Record<PlayerTheme, string> = { continuation: 'settings_page.theme_continuation', colors: 'settings_page.theme_colors', blur: 'settings_page.theme_blur' };
const THEME_DETAIL: Record<PlayerTheme, string> = {
  continuation: 'settings_page.theme_continuation_detail',
  colors: 'settings_page.theme_colors_detail',
  blur: 'settings_page.theme_blur_detail',
};

const SHELF_LABELS: Record<LocalShelfKind, string> = {
  LOOP: 'home_shelf_title_loop',
  REDISCOVER: 'home_shelf_title_rediscover',
  RECENTLY_ADDED: 'home_shelf_title_recently_added',
  PLAYLISTS: 'home_shelf_title_playlists',
  ARTIST_MIX: 'settings_page.shelf_artist_mix',
  LIBRARY_SHUFFLE: 'home_shelf_title_library_shuffle',
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
  if (auth.state === 'checking') return translateUi('settings_page.checking');
  if (auth.state === 'unavailable') return translateUi('settings_page.needs_the_extension');
  const { browserSignedIn, connected, active } = auth.auth;
  if (!browserSignedIn) return translateUi('settings_page.not_signed_in');
  if (!connected) return translateUi('settings_page.disconnected');
  return active ? active.name : translateUi('settings_page.signed_in');
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
        ? translateUi('settings_page.on')
        : translateUi('settings_page.off')
      : discord.state === 'ready' && discord.discord.available
        ? translateUi('integration_settings.not_connected')
        : null;

  return (
    <div className={settingsUi.centered}>
      <PageHeader title={translateUi("settings_page.settings")} />
      <div className={settingsUi.body}>
        <h2 className={settingsUi.sectionHeader}>{translateUi("settings_page.account_integrations")}</h2>
        <section className={settingsUi.group}>
          <div className={settingsUi.integration}>
            <Glyph Icon={SiYoutubemusic} color="#ffffff" ink="#ff0033" />
            <span className={settingsUi.integrationName}>YouTube Music</span>
            <span className={settingsUi.value}>{youtubeText(yt)}</span>
          </div>
          <div className={settingsUi.integration}>
            <Glyph Icon={SiSpotify} color="#1ed760" />
            <span className={settingsUi.integrationName}>Spotify</span>
            {present ? <SpotifyIntegration /> : <span className={settingsUi.value}>{translateUi("settings_page.needs_the_extension")}</span>}
          </div>
          {present && discord.state === 'ready' && discord.discord.available && (
            <LinkRow to="/settings/discord" icon={<Glyph Icon={SiDiscord} color="#5865f2" />} title="Discord" value={discordText} />
          )}
          <LinkRow
            to="/extension"
            icon={<Glyph Icon={MdOutlineExtension} color="#636366" />}
            title={translateUi("settings_page.auden_extension")}
            value={present ? `v${extension.extension.info.version}` : translateUi('pages_extensionpage_not_installed')}
          />
        </section>
        {config.connectUrl && (
          <section className={`${settingsUi.group} mt-3`}>
            <Toggle
              label={translateUi("settings_page.play_on_my_other_devices")}
              detail={translateUi("settings_page.shows_your_other_devices_with")}
              checked={connectEnabled}
              onChange={setConnectEnabled}
            />
          </section>
        )}
        <section className={`${settingsUi.group} mt-3 empty:hidden`}>
          <Toggle
            label={translateUi("settings_page.share_listening_activity_to_youtube")}
            detail={
              present && !extension.extension.info.capabilities.includes('yt.stats')
                ? translateUi("pages_settingspage_update_the_auden_extension_to")
                : translateUi("pages_settingspage_songs_you_play_here_go")
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
            <span className={settingsUi.integrationName}>{translateUi("settings_page.extension_access")}</span>
            {present ? <AppleMusicIntegration /> : <span className={settingsUi.value}>{translateUi("settings_page.needs_the_extension")}</span>}
          </div>
          <AppleMusicToggles />
          <ClipNetworkToggle />
        </section>
        <p className={settingsUi.footer}>{translateUi("settings_page.from_apple_music_s_public")}</p>

        <h2 className={settingsUi.sectionHeader}>{translateUi("settings_page.home")}</h2>
        <section className={settingsUi.group}>
          <div className={settingsUi.integration}>
            <span className={settingsUi.integrationName}>{translateUi("settings_page.your_music_on_home")}</span>
            <span className={settingsUi.value}>{home.localWeight === 0 ? translateUi("pages_settingspage_feed_only") : `${home.localWeight}%`}</span>
          </div>
          <div className="px-4 pb-3">
            <input
              type="range"
              min={0}
              max={100}
              step={5}
              value={home.localWeight}
              onChange={(e) => home.setLocalWeight(Number(e.target.value))}
              aria-label={translateUi("settings_page.how_much_of_home_is")}
              className="w-full accent-[var(--keyColor,currentColor)]"
            />
            <div className="flex justify-between type-callout text-secondary">
              <span>YouTube Music</span>
              <span>{translateUi("settings_page.yours")}</span>
            </div>
          </div>
          {LOCAL_SHELF_KINDS.map((kind) => (
            <Toggle key={kind} label={translateUi(SHELF_LABELS[kind] as keyof typeof import('@/i18n.en.json'))} checked={home.enabled[kind]} onChange={(on) => home.setShelf(kind, on)} />
          ))}
          <Toggle label={translateUi("settings_page.hide_recently_played")} detail={translateUi("settings_page.takes_the_shelf_off_home")} checked={home.hideRecents} onChange={home.setHideRecents} />
          <Toggle label={translateUi("settings_page.hide_music_videos")} detail={translateUi("settings_page.takes_them_off_home_s")} checked={home.hideVideos} onChange={home.setHideVideos} />
          <Toggle label={translateUi("settings_page.square_video_thumbnails")} detail={translateUi("settings_page.music_videos_show_as_squares")} checked={home.squareVideoThumbnails} onChange={home.setSquareVideoThumbnails} />
        </section>
        <section className={`${settingsUi.group} mt-3`}>
          <label className={settingsUi.controlRow}>
            <span className={settingsUi.rowText}>
              <span>{translateUi("settings_page.player_background")}</span>
              <span className={settingsUi.rowDetail}>{translateUi(THEME_DETAIL[look.theme] as keyof typeof import('@/i18n.en.json'))}</span>
            </span>
            <Select<PlayerTheme>
              label={translateUi("settings_page.player_background")}
              value={look.theme}
              options={PLAYER_THEMES.map((t) => ({ value: t, label: translateUi(THEME_LABELS[t] as keyof typeof import('@/i18n.en.json')) }))}
              onChange={look.setTheme}
            />
          </label>
          <Toggle
            label={translateUi("settings_page.full_screen_covers")}
            detail={translateUi("settings_page.album_and_playlist_covers_run")}
            checked={look.fullBleedCovers}
            onChange={look.setFullBleedCovers}
          />
        </section>
        <p className={settingsUi.footer}>{translateUi("settings_page.built_from_what_you_play")}</p>

        <h2 className={settingsUi.sectionHeader}>{translateUi("settings_page.auden_dj")}</h2>
        <section className={settingsUi.group}>
          <Toggle label={translateUi("settings_page.voice")} detail={translateUi("settings_page.the_dj_speaks_between_sets")} checked={dj.voice} onChange={(voice) => dj.set({ voice })} />
          <div className={settingsUi.integration}>
            <span className={settingsUi.integrationName}>{translateUi("settings_page.language")}</span>
            <select className="rounded-md bg-quinary px-2 py-1 type-callout text-primary" value={dj.language} onChange={(e) => dj.set({ language: e.target.value === 'es' ? 'es' : 'en' })} aria-label={translateUi("settings_page.dj_language")}>
              <option value="en">{translateUi("settings_page.english")}</option>
              <option value="es">{translateUi("settings_page.spanish")}</option>
            </select>
          </div>
          <div className={settingsUi.integration}>
            <span className={settingsUi.integrationName}>{translateUi("settings_page.songs_per_set")}</span>
            <span className={settingsUi.value}>{dj.blockSize}</span>
          </div>
          <div className="px-4 pb-3">
            <input type="range" min={5} max={12} step={1} value={dj.blockSize} onChange={(e) => dj.set({ blockSize: Number(e.target.value) })} aria-label={translateUi("settings_page.songs_per_dj_set")} className="w-full" />
          </div>
        </section>
        <p className={settingsUi.footer}>{translateUi("settings_page.start_the_dj_from_the")}</p>

        <h2 className={settingsUi.sectionHeader}>{translateUi("settings_page.local_library")}</h2>
        <section className={settingsUi.group}>
          <LinkRow to="/local" title={translateUi("settings_page.on_this_device")} value={translateUi("settings_page.folders_and_songs")} />
          <Toggle
            label={translateUi("settings_page.keep_saved_playlists_up_to")}
            detail={translateUi("settings_page.adds_new_songs_from_youtube")}
            checked={autoSync.enabled}
            onChange={autoSync.setEnabled}
          />
          <Toggle
            label={translateUi("settings_page.also_when_data_saver_is")}
            detail={translateUi("settings_page.for_saved_copies_of_youtube")}
            checked={autoSync.alsoOnDataSaver}
            onChange={autoSync.setAlsoOnDataSaver}
          />
        </section>
        <p className={settingsUi.footer}>
          {fsaSupported()
            ? translateUi("pages_settingspage_your_browser_remembers_the_folders")
            : translateUi("pages_settingspage_this_browser_can_t_keep")}
        </p>

        <h2 className={settingsUi.sectionHeader}>{translateUi("settings_page.listen_together")}</h2>
        <section className={settingsUi.group}>
          <LinkRow
            to="/settings/listen-together"
            icon={<Glyph Icon={Users} color="#fa2d48" />}
            title={translateUi("settings_page.listen_together")}
            value={party.code ? translateUi('settings_page.in_party', { code: party.code }) : translateUi('settings_page.listen_in_sync_with_friends')}
          />
        </section>

        <h2 className={settingsUi.sectionHeader}>{translateUi("settings_page.playback")}</h2>
        <section className={settingsUi.group}>
          <Toggle
            label={translateUi("settings_page.autoplay")}
            detail={translateUi("settings_page.when_the_queue_ends_keep")}
            checked={autoplay}
            onChange={setAutoplay}
          />
          <label className={settingsUi.controlRow}>
            <span>{translateUi("settings_page.crossfade")}</span>
            <Select<number | 'automix'>
              label={translateUi("settings_page.crossfade")}
              value={automix ? 'automix' : crossfadeSec}
              options={[
                ...CROSSFADE_CHOICES.map((seconds) => ({ value: seconds, label: seconds === 0 ? translateUi('settings_page.off') : translateUi('settings_page.seconds_count', { count: seconds }) })),
                { value: 'automix', label: 'Automix' },
              ]}
              onChange={(value) => (value === 'automix' ? setAutomix(true) : setCrossfade(value))}
            />
          </label>
          {automix && (
            <label className={settingsUi.controlRow}>
              <span>{translateUi("settings_page.blend_length")}</span>
              <Select
                label={translateUi("settings_page.blend_length")}
                value={automixBars}
                options={AUTOMIX_BARS.map((bars) => ({ value: bars, label: `Up to ${bars} bars` }))}
                onChange={setAutomixBars}
              />
            </label>
          )}
          <Toggle
            label={translateUi("settings_page.prefer_music_only_version")}
            detail={translateUi("settings_page.music_videos_play_as_their")}
            checked={musicOnly}
            onChange={(on) => useMusicOnly.getState().setPreferMusicOnly(on)}
          />
          <Toggle
            label={translateUi("settings_page.show_stats_for_nerds")}
            detail={translateUi("settings_page.codec_bitrate_and_sample_rate")}
            checked={showNerdStats}
            onChange={setShowNerdStats}
          />
        </section>
        {automix && (
          <p className={settingsUi.footer}>{translateUi("settings_page.automix_finds_each_song_s")}</p>
        )}

        <h2 className={settingsUi.sectionHeader}>{translateUi("settings_page.lyrics")}</h2>
        <section className={settingsUi.group}>
          <button className={`${settingsUi.integrationPlain} ${settingsUi.linkRow}`} onClick={() => setLyrics(true)}>
            <span className={settingsUi.integrationName}>{translateUi("settings_page.lyric_sources")}</span>
            <span className={settingsUi.value}>
              {translateUi('settings_page.order_sources_syllable_timing')}
              <ChevronRight size={16} />
            </span>
          </button>
          <div className={settingsUi.integrationPlain}>
            <span className={settingsUi.integrationName}>{translateUi("settings_page.lyric_sources_access")}</span>
            {present ? <LyricSourcesAccess /> : <span className={settingsUi.value}>{translateUi("settings_page.needs_the_extension")}</span>}
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
