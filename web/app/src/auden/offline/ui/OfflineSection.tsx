import { t as translateUi } from '@/i18n';
import { Link } from 'react-router';
import { Toggle } from '@/pages/IntegrationSettings';
import { settingsUi } from '@/pages/settingsStyles';
import { FolderSettings } from '../folder/FolderSettings';
import { getOfflineRuntime } from '../runtime';
import { useOfflineSettings } from '../settings';

/** Settings › Offline copies: the switch (off by default), what it does, and the way to the manager. */
export function OfflineSection() {
  const { enabled, withLyrics, keepUpdated, setEnabled, setWithLyrics, setKeepUpdated } = useOfflineSettings();
  const supported = getOfflineRuntime().supported;
  return (
    <>
      <h2 className={settingsUi.sectionHeader}>{translateUi("offline_section.offline_copies")}</h2>
      <section className={settingsUi.group}>
        <Toggle
          label={translateUi("offline_section.save_songs_for_offline_listening")}
          detail={supported ? translateUi("offline_ui_offlinesection_offers_save_for_offline_on") : translateUi("offline_ui_offlinesection_this_browser_can_t_store")}
          checked={enabled && supported}
          onChange={(on) => supported && setEnabled(on)}
        />
        {enabled && supported && (
          <>
            <Toggle label={translateUi("offline_section.find_lyrics_for_them")} detail={translateUi("offline_section.saved_with_each_song_so")} checked={withLyrics} onChange={setWithLyrics} />
            <Toggle
              label={translateUi("offline_section.keep_playlists_updated")}
              detail={translateUi("offline_section.a_playlist_you_saved_whole")}
              checked={keepUpdated}
              onChange={setKeepUpdated}
            />
            <Link className={`${settingsUi.integrationPlain} ${settingsUi.linkRow}`} to="/offline">
              <span className={settingsUi.integrationName}>{translateUi("offline_section.manage_offline_copies")}</span>
              <span className={settingsUi.value}>{translateUi("offline_section.progress_failures_space_used")}</span>
            </Link>
            <FolderSettings />
          </>
        )}
      </section>
      <p className={settingsUi.footer}>{translateUi("offline_section.fetches_the_same_audio_youtube")}</p>
    </>
  );
}
