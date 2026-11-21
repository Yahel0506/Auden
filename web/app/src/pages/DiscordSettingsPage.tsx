import { t as translateUi } from '@/i18n';
import { PageHeader } from '@/components/PageHeader';
import { useDiscord } from '@/extension/useDiscord';
import { DiscordIntegration, DiscordSettings } from './IntegrationSettings';
import { settingsUi } from './settingsStyles';

/** Discord rich presence (DiscordScreen on the phone): the account, then how the card reads. */
export default function DiscordSettingsPage() {
  const status = useDiscord();
  return (
    <div className={settingsUi.centered}>
      <PageHeader title="Discord" />
      <div className={settingsUi.body}>
        <section className={settingsUi.group}>
          <div className={settingsUi.integrationPlain}>
            <span className={settingsUi.integrationName}>{translateUi("discord_settings_page.account")}</span>
            <DiscordIntegration />
          </div>
        </section>
        {status.state === 'ready' && !status.discord.connected && (
          <p className={settingsUi.footer}>{translateUi("discord_settings_page.connect_opens_a_page_from")}</p>
        )}
        {status.state === 'ready' && status.discord.connected && (
          <>
            <h2 className={settingsUi.sectionHeader}>{translateUi("discord_settings_page.presence")}</h2>
            <DiscordSettings />
          </>
        )}
      </div>
    </div>
  );
}
