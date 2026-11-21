import { describe, expect, it } from 'vitest';
import { resolveUiLocale } from './i18n';

describe('resolveUiLocale', () => {
  it('uses the first supported browser language', () => {
    expect(resolveUiLocale(['en-US', 'es-MX'])).toBe('en');
    expect(resolveUiLocale(['fr-CA', 'en-US'])).toBe('fr');
  });

  it('preserves the requested Chinese script when its region conflicts', () => {
    expect(resolveUiLocale(['zh-Hans-TW'])).toBe('zh-CN');
    expect(resolveUiLocale(['zh-Hant-CN'])).toBe('zh-TW');
    expect(resolveUiLocale(['zh-HK'])).toBe('zh-TW');
  });

  it('skips unsupported and malformed preferences', () => {
    expect(resolveUiLocale(['not-a-locale', 'ar', 'es-419'])).toBe('es');
    expect(resolveUiLocale(['ar'])).toBe('en');
  });
});
