export type MessageKey =
  | 'extensionName'
  | 'extensionDescription'
  | 'openAuden'
  | 'checking'
  | 'notSignedIn'
  | 'signIn'
  | 'couldNotReadAccount'
  | 'youtubeSessionRejected'
  | 'youtubeAccountMissing'
  | 'youtubeAccountUnavailable'
  | 'disconnected'
  | 'retry'
  | 'connect'
  | 'disconnect'
  | 'spotifySignInExpired'
  | 'spotifyRateLimited'
  | 'spotifyConnectionFailed'
  | 'spotifySignInFailed'
  | 'stopUsingAccountYouTube'
  | 'stopUsingAccountSpotify'
  | 'notConnected'
  | 'showingWhatYouPlay'
  | 'connecting'
  | 'couldNotConnect'
  | 'onWaitingForSong'
  | 'presenceOff'
  | 'forgetDiscordAccount'
  | 'couldNotReadSession'
  | 'allowLyricSourcesTitle'
  | 'allowLyricSourcesHeading'
  | 'allowLyricSourcesExplanation'
  | 'allow'
  | 'permissionDone'
  | 'permissionDenied'
  | 'discordConnectTitle'
  | 'discordConnectHeading'
  | 'discordConnectExplanation'
  | 'discordTokenWarning'
  | 'connectDiscord'
  | 'discordPermissionDenied'
  | 'discordConnected'
  | 'discordSignInProgress'
  | 'discordReadProgress'
  | 'discordSessionMissing'
  | 'discordTokenRejected'
  | 'discordConnectionError';

export function message(key: MessageKey, substitutions?: string | string[]): string {
  const getMessage = browser.i18n.getMessage as (name: string, replacements?: string | string[]) => string;
  const localized = getMessage(key, substitutions);
  if (!localized) throw new Error(`Missing extension message: ${key}`);
  return localized;
}

export function applyDocumentLocale(): void {
  document.documentElement.lang = browser.i18n.getUILanguage();
  document.documentElement.dir = browser.i18n.getMessage('@@bidi_dir') || 'ltr';
}

export function localizeStaticDocument(): void {
  applyDocumentLocale();
  document.querySelectorAll<HTMLElement>('[data-i18n]').forEach((element) => {
    const key = element.dataset.i18n as MessageKey | undefined;
    if (key) element.textContent = message(key);
  });
  document.querySelectorAll<HTMLElement>('[data-i18n-title]').forEach((element) => {
    const key = element.dataset.i18nTitle as MessageKey | undefined;
    if (key) element.title = message(key);
  });
  const title = document.querySelector<HTMLTitleElement>('title[data-i18n]');
  if (title?.dataset.i18n) document.title = message(title.dataset.i18n as MessageKey);
  document.body.dataset.i18nReady = 'true';
}

/** Convert known Discord connection diagnostics to localized UI copy. */
export function discordErrorMessage(error: unknown): string {
  const detail = error instanceof Error ? error.message : String(error);
  if (/No Discord session turned up/i.test(detail)) return message('discordSessionMissing');
  if (/Discord refused the token/i.test(detail)) return message('discordTokenRejected');
  if (/Not allowed\. Discord stays off\./i.test(detail)) return message('discordPermissionDenied');
  return message('discordConnectionError');
}

export function youtubeProblemMessage(problem: string | null): string {
  if (!problem) return message('couldNotReadAccount');
  if (/signed out|session wasn't accepted|no signed-in Google account/i.test(problem)) {
    return /no signed-in Google account/i.test(problem)
      ? message('youtubeAccountMissing')
      : message('youtubeSessionRejected');
  }
  if (/account_menu|accounts_list/i.test(problem)) return message('youtubeAccountUnavailable');
  return message('couldNotReadAccount');
}

export function spotifyProblemMessage(kind: string | undefined): string {
  switch (kind) {
    case 'signed_out':
    case 'expired':
      return message('spotifySignInExpired');
    case 'rate_limited':
      return message('spotifyRateLimited');
    case 'network':
      return message('spotifyConnectionFailed');
    default:
      return message('spotifySignInFailed');
  }
}
