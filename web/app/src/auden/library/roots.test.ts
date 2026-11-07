// @vitest-environment happy-dom
import 'fake-indexeddb/auto';
import { IDBFactory } from 'fake-indexeddb';
import { afterEach, beforeEach, describe, expect, it } from 'vitest';
import { libraryRoots } from '../db/libraryDao';
import { closeAudenDb } from '../db/schema';
import { adoptPickedFiles, sessionFiles } from './roots';

beforeEach(() => {
  globalThis.indexedDB = new IDBFactory();
  sessionFiles.clear();
});
afterEach(async () => {
  await closeAudenDb();
});

const pick = (...rels: string[]): File[] =>
  rels.map((rel) => {
    const f = new File(['x'], rel.split('/').pop()!);
    Object.defineProperty(f, 'webkitRelativePath', { value: rel });
    return f;
  });

describe('adoptPickedFiles (Firefox)', () => {
  it('adding never joins an existing root by name: another "Music" is refused, not adopted', async () => {
    const first = await adoptPickedFiles(pick('Music/a.mp3'));
    expect(first).toMatchObject({ kind: 'files', name: 'Music' });
    const second = await adoptPickedFiles(pick('Music/other.mp3'));
    expect(second).toEqual({ duplicate: 'Music' });
    expect(await libraryRoots.all()).toHaveLength(1);
  });
  it('reconnecting binds to the root only when the folder name matches', async () => {
    const root = (await adoptPickedFiles(pick('Music/a.mp3'))) as { id: string };
    sessionFiles.clear();
    expect(await adoptPickedFiles(pick('Elsewhere/a.mp3'), root.id)).toBeNull();
    expect(sessionFiles.has(root.id)).toBe(false);
    expect(await adoptPickedFiles(pick('Music/a.mp3', 'Music/sub/b.mp3'), root.id)).toMatchObject({ id: root.id });
    expect([...sessionFiles.get(root.id)!.keys()]).toEqual(['a.mp3', 'sub/b.mp3']);
  });
});
