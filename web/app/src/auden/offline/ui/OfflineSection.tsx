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
      <h2 className={settingsUi.sectionHeader}>Offline copies</h2>
      <section className={settingsUi.group}>
        <Toggle
          label="Save songs for offline listening"
          detail={supported ? 'Offers “Save for Offline” on songs, albums and playlists.' : 'This browser can’t store files for it.'}
          checked={enabled && supported}
          onChange={(on) => supported && setEnabled(on)}
        />
        {enabled && supported && (
          <>
            <Toggle label="Find lyrics for them" detail="Saved with each song, so they show offline too." checked={withLyrics} onChange={setWithLyrics} />
            <Toggle
              label="Keep playlists updated"
              detail="A playlist you saved whole fetches its new songs too, and writes them to your folder if you saved it there. Not on Save-Data or a cellular link, and it asks first when more than 50 songs are new."
              checked={keepUpdated}
              onChange={setKeepUpdated}
            />
            <Link className={`${settingsUi.integrationPlain} ${settingsUi.linkRow}`} to="/offline">
              <span className={settingsUi.integrationName}>Manage offline copies</span>
              <span className={settingsUi.value}>Progress, failures, space used</span>
            </Link>
            <FolderSettings />
          </>
        )}
      </section>
      <p className={settingsUi.footer}>
        Fetches the same audio YouTube Music plays for you, into this browser only, so you can listen without a connection. Needs the
        extension to download (not to play). It keeps playing in an open tab but the site itself still needs a connection to load, and
        copying audio may go against YouTube’s terms of service; it is your choice. Experimental.
      </p>
    </>
  );
}
