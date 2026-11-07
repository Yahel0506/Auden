// @vitest-environment happy-dom
import 'fake-indexeddb/auto';
import { IDBFactory } from 'fake-indexeddb';
import { existsSync, readFileSync, writeFileSync } from 'node:fs';
import { dirname, join } from 'node:path';
import { fileURLToPath } from 'node:url';
import { afterEach, beforeEach, describe, expect, it } from 'vitest';
import { closeAudenDb } from '../db/schema';
import { buildBackup, readSnapshot } from './export';
import { parseBackup } from './format';
import { importBackup } from './import';
import { forgetHeldSettings, readHeldSettings } from './passthrough';

/**
 * Android -> web -> Android. The phone's own deserializer (BackupWebFileTest.kt, a JUnit test) reads the file this
 * produces, so the claim "the phone accepts what the web writes" is checked by the phone's code, not by this
 * module's reading of its declarations. The generated file is committed and this test fails when it goes stale:
 *   UPDATE_BACKUP_FIXTURES=1 pnpm --dir app exec vitest run src/auden/backup/roundTrip.test.ts
 */
const dir = join(dirname(fileURLToPath(import.meta.url)), 'fixtures');
const GENERATED = join(dir, 'web-export.generated.json');
const NOW = new Date('2026-11-06T12:00:00.000Z');

beforeEach(() => {
  globalThis.indexedDB = new IDBFactory();
  localStorage.clear();
});
afterEach(async () => {
  await closeAudenDb();
  forgetHeldSettings();
});

describe('Android golden -> web -> file for the phone', () => {
  it('produces the committed fixture the Kotlin test decodes (and says when it is stale)', async () => {
    const golden = readFileSync(join(dir, 'android-golden.json'), 'utf8');
    await importBackup(parseBackup(golden, 1));
    const { file } = buildBackup(await readSnapshot(), readHeldSettings(), { now: NOW, versionName: 'web' });
    const text = `${JSON.stringify(file, null, 2)}\n`;
    if (process.env.UPDATE_BACKUP_FIXTURES === '1' || !existsSync(GENERATED)) writeFileSync(GENERATED, text);
    expect(readFileSync(GENERATED, 'utf8')).toBe(text);
  });

  it('keeps what the phone sent, so the phone gets it back: the settings verbatim and the phone files in their own fields', async () => {
    const golden = parseBackup(readFileSync(join(dir, 'android-golden.json'), 'utf8'), 1);
    await importBackup(golden);
    const { file } = buildBackup(await readSnapshot(), readHeldSettings(), { now: NOW });
    expect(file.settings).toEqual(golden.settings);
    const phoneUris = golden.library!.tracks.map((t) => t.localUri).filter(Boolean).sort();
    expect(file.library!.tracks.map((t) => t.localUri).filter(Boolean).sort()).toEqual(phoneUris);
    expect(file.library!.playlists.map((p) => p.id).sort()).toEqual(golden.library!.playlists.map((p) => p.id).sort());
    expect(file.library!.lyricsVariants).toHaveLength(golden.library!.lyricsVariants.length);
    expect(file.listening.map((b) => b.month)).toEqual(golden.listening.map((b) => b.month).sort());
  });
});
