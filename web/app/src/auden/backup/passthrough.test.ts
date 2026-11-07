// @vitest-environment happy-dom
import { beforeEach, describe, expect, it } from 'vitest';
import { forgetHeldSettings, holdSettings, readHeldSettings } from './passthrough';

const settings = { a: { type: 'bool', value: 'true' } };

beforeEach(() => {
  localStorage.clear();
  forgetHeldSettings();
});

describe('holdSettings', () => {
  it('records an Android file\'s settings with the day that file was exported, and the build that wrote it', () => {
    holdSettings({ settings, exportedAt: '2026-10-03T08:00:00Z', versionName: '1.10.2' });
    expect(readHeldSettings()).toEqual({ settings, capturedAt: '2026-10-03T08:00:00Z', versionName: '1.10.2' });
  });

  it('for a file the web wrote, records when the settings were captured, not when the web exported, and keeps the phone\'s build', () => {
    holdSettings({ settings, exportedAt: '2026-10-03T08:00:00Z', versionName: '1.10.2' });
    holdSettings({ settings, exportedAt: '2026-11-06T12:00:00Z', versionName: 'web', origin: 'web', settingsCapturedAt: '2026-10-03T08:00:00Z' });
    expect(readHeldSettings()).toMatchObject({ capturedAt: '2026-10-03T08:00:00Z', versionName: '1.10.2' });
  });

  it('a web file with no capture date falls back to its own, and leaves the build unknown rather than claiming "web"', () => {
    holdSettings({ settings, exportedAt: '2026-11-06T12:00:00Z', versionName: 'web', origin: 'web' });
    expect(readHeldSettings()).toMatchObject({ capturedAt: '2026-11-06T12:00:00Z', versionName: '' });
  });

  it('a file with no settings leaves the ones already held alone', () => {
    holdSettings({ settings, exportedAt: '2026-10-03T08:00:00Z', versionName: '1.10.2' });
    holdSettings({ settings: {}, exportedAt: '2026-11-06T12:00:00Z', versionName: 'web', origin: 'web' });
    expect(readHeldSettings()?.capturedAt).toBe('2026-10-03T08:00:00Z');
  });
});
