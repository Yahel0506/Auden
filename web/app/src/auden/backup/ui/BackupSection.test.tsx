// @vitest-environment happy-dom
import 'fake-indexeddb/auto';
import { IDBFactory } from 'fake-indexeddb';
import { act, createElement } from 'react';
import { createRoot, type Root } from 'react-dom/client';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { closeAudenDb, openAudenDb } from '../../db/schema';
import golden from '../fixtures/android-golden.json';
import bitchord from '../fixtures/bitchord-v1.json';

(globalThis as { IS_REACT_ACT_ENVIRONMENT?: boolean }).IS_REACT_ACT_ENVIRONMENT = true;

const downloads = vi.hoisted(() => [] as { name: string; text: string }[]);
vi.mock('@/auden/lyrics/actions', () => ({ downloadText: (name: string, text: string) => downloads.push({ name, text }) }));

const { BackupSection } = await import('./BackupSection');
const { readHeldSettings, forgetHeldSettings } = await import('../passthrough');

let root: Root;
let host: HTMLElement;
beforeEach(async () => {
  globalThis.indexedDB = new IDBFactory();
  localStorage.clear();
  downloads.length = 0;
  host = document.createElement('div');
  document.body.appendChild(host);
  root = createRoot(host);
  await act(async () => root.render(createElement(BackupSection)));
});
afterEach(async () => {
  await act(async () => root.unmount());
  host.remove();
  await closeAudenDb();
  forgetHeldSettings();
});

const button = (text: string) => [...host.querySelectorAll('button')].find((b) => b.textContent?.trim() === text) as HTMLButtonElement | undefined;
const settle = (ms = 60) => act(async () => { await new Promise((r) => setTimeout(r, ms)); });

async function choose(content: unknown | string, name = 'backup.json') {
  const input = host.querySelector('input[type=file]') as HTMLInputElement;
  const file = new File([typeof content === 'string' ? content : JSON.stringify(content)], name, { type: 'application/json' });
  Object.defineProperty(input, 'files', { value: [file], configurable: true });
  await act(async () => { input.dispatchEvent(new Event('change', { bubbles: true })); });
  await settle(120);
}

describe('exporting', () => {
  it('asks for an acknowledgement when there are no phone settings to send back, and not otherwise', async () => {
    expect(button('Export')!.disabled).toBe(true);
    expect(host.textContent).toContain('This browser has no settings from your phone to send back');
    await act(async () => (host.querySelector('input[type=checkbox]') as HTMLInputElement).click());
    expect(button('Export')!.disabled).toBe(false);
  });

  it('downloads the file under Android\'s name, in the shape the phone reads, and says what stayed behind', async () => {
    await act(async () => (host.querySelector('input[type=checkbox]') as HTMLInputElement).click());
    await act(async () => button('Export')!.click());
    await settle();
    expect(downloads).toHaveLength(1);
    expect(downloads[0]!.name).toMatch(/^auden-backup-\d{4}-\d{2}-\d{2}\.json$/);
    expect(JSON.parse(downloads[0]!.text)).toMatchObject({ app: 'auden', version: 2, origin: 'web' });
    expect(host.querySelector('[role=status]')?.textContent).toContain('Exported 0 songs');
  });
});

describe('restoring', () => {
  it('reads the file, shows what will be replaced, and changes NOTHING until the listener confirms', async () => {
    await choose(golden);
    const dialog = host.querySelector('[role=alertdialog]');
    expect(dialog?.textContent).toContain('This replaces the library in this browser');
    expect(dialog?.textContent).toContain('Replace and Restore');
    const db = await openAudenDb();
    expect(await db.count('track')).toBe(0);
    expect(await db.count('playlist')).toBe(0);
  });

  it('restores on confirmation, shows the result, keeps the phone\'s settings to hand back, and offers a reload', async () => {
    await choose(golden);
    await act(async () => button('Replace and Restore')!.click());
    await settle(200);
    expect(host.textContent).toContain('Restored.');
    expect(host.textContent).toContain('Reload');
    const db = await openAudenDb();
    expect(await db.count('track')).toBeGreaterThan(0);
    expect(await db.count('playlist')).toBe(2);
    expect(await db.count('listening')).toBe(2);
    expect(Object.keys(readHeldSettings()!.settings).length).toBeGreaterThan(0);
  });

  it('cancel leaves everything as it was and drops the file', async () => {
    await choose(golden);
    await act(async () => button('Cancel')!.click());
    expect(host.querySelector('[role=alertdialog]')).toBeNull();
    expect(await (await openAudenDb()).count('track')).toBe(0);
  });

  it('a BitChord file says it replaces only the listening, and leaves the library alone', async () => {
    const db = await openAudenDb();
    await db.add('playlist', { id: 'sp_local_1', title: 'Mine', createdAt: 1, updatedAt: 1, pinned: false, sourceId: null, coverUrl: null });
    await choose(bitchord);
    expect(host.querySelector('[role=alertdialog]')?.textContent).toContain('only your listening history is replaced');
    await act(async () => button('Replace and Restore')!.click());
    await settle(150);
    expect(await db.count('playlist')).toBe(1);
    expect(await db.count('listening')).toBeGreaterThan(0);
  });

  it('refuses a file that is not an Auden backup, with the reason, and touches nothing', async () => {
    await choose('{"hello": "world"}');
    expect(host.querySelector('[role=alert]')?.textContent).toContain('doesn’t look like an Auden backup');
    expect(host.querySelector('[role=alertdialog]')).toBeNull();
    await choose({ app: 'auden', version: 3 });
    expect(host.querySelector('[role=alert]')?.textContent).toContain('newer version of Auden');
    await choose({ app: 'spotify', version: 1 });
    expect(host.querySelector('[role=alert]')?.textContent).toContain('another app');
  });

  it('offers a copy of what is here before it is replaced, under the same acknowledgement as Export', async () => {
    const db = await openAudenDb();
    await db.add('playlist', { id: 'sp_local_1', title: 'Before', createdAt: 1, updatedAt: 1, pinned: false, sourceId: null, coverUrl: null });
    await choose(golden);
    expect(button('Export a copy first')!.disabled).toBe(true);
    await act(async () => button('Export a copy first')!.click());
    expect(downloads).toHaveLength(0);
    await act(async () => (host.querySelector('input[type=checkbox]') as HTMLInputElement).click());
    expect(button('Export a copy first')!.disabled).toBe(false);
    await act(async () => button('Export a copy first')!.click());
    await settle();
    // The copy is of what is here NOW, not of the file chosen.
    expect(downloads).toHaveLength(1);
    expect(JSON.parse(downloads[0]!.text).library.playlists.map((p: { title: string }) => p.title)).toEqual(['Before']);
  });
});
