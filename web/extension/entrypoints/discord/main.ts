import '../popup/style.css';
import '../grant/style.css';
import { connectFromTab, DISCORD_ORIGINS } from '../../lib/discord';
import { discordErrorMessage, localizeStaticDocument, message } from '../../lib/i18n';

localizeStaticDocument();

// Opened by discord.connect: the browser only asks for discord.com after a
// click on one of the extension's own pages, and the token is only read on it.
const status = document.getElementById('status')!;
const button = document.getElementById('connect') as HTMLButtonElement;

button.addEventListener('click', async () => {
  const granted = await browser.permissions.request({ origins: DISCORD_ORIGINS }).catch(() => false);
  if (!granted) {
    status.textContent = message('discordPermissionDenied');
    return;
  }
  button.disabled = true;
  try {
    const state = await connectFromTab((progress) => {
      const key = progress.startsWith('Sign in') ? 'discordSignInProgress' : 'discordReadProgress';
      status.textContent = message(key);
    });
    status.textContent = message('discordConnected', state.user?.name ?? 'your account');
    setTimeout(() => window.close(), 1500);
  } catch (error) {
    console.error('Auden: Discord connection failed', error);
    status.textContent = discordErrorMessage(error);
    button.disabled = false;
  }
});
