import type { BackupFile, PrefValue } from './format';

/**
 * The phone's settings, kept to hand back. The web has none of Android's preferences and never applies them,
 * but a restore on the phone REPLACES its whole preference file with whatever the backup says: a backup the web
 * writes with none would reset them. So the settings of the last Android backup imported are held here
 * (localStorage, the one place the web keeps small things) and written back unchanged on export.
 * NOTE: the phone's search history shares that preference file, so it lives here too.
 */
const KEY = 'auden:backup:androidSettings';

export interface HeldSettings {
  settings: Record<string, PrefValue>;
  /** The `exportedAt` of the backup they came from: shown, so the listener knows how old the phone's settings are. */
  capturedAt: string;
  versionName: string;
}

export function readHeldSettings(): HeldSettings | null {
  try {
    const parsed = JSON.parse(localStorage.getItem(KEY) ?? 'null') as HeldSettings | null;
    return parsed && typeof parsed.settings === 'object' && parsed.settings ? parsed : null;
  } catch {
    return null;
  }
}

/**
 * Holds [file]'s settings verbatim (a BitChord file's included). Only called once the import has committed.
 * A file the web wrote carries settings that are older than the file: the date they were captured is its
 * `settingsCapturedAt`, not the day the web exported it, and the build that wrote them was the phone's, not
 * the web's. Taking `exportedAt` would tell the listener, and the next export, that they are newer than they are.
 */
export function holdSettings(file: Pick<BackupFile, 'settings' | 'exportedAt' | 'versionName' | 'origin' | 'settingsCapturedAt'>): void {
  try {
    if (Object.keys(file.settings).length === 0) return;
    const fromWeb = file.origin === 'web';
    const before = readHeldSettings();
    localStorage.setItem(
      KEY,
      JSON.stringify({
        settings: file.settings,
        capturedAt: fromWeb ? (file.settingsCapturedAt ?? file.exportedAt) : file.exportedAt,
        versionName: fromWeb ? (before?.versionName ?? '') : file.versionName,
      } satisfies HeldSettings),
    );
  } catch {
    // A full store only costs the round trip of the phone's settings.
  }
}

export function forgetHeldSettings(): void {
  try {
    localStorage.removeItem(KEY);
  } catch {
    // Nothing to forget.
  }
}
