import { create } from 'zustand';
import { libraryRoots } from '../../db/libraryDao';
import { planGone, plausiblySameFolder } from './diff';
import { applyBatch, dropFiles, knownFiles } from './apply';
import type { ScanMessage, ScanRequest, ScanSource } from './protocol';
import { sessionFiles, useRoots } from '../roots';

export interface ScanState {
  scanning: boolean;
  rootId: string | null;
  seen: number;
  parsed: number;
  error: string | null;
  /** The last finished scan: what it added, changed and removed. */
  last: { added: number; changed: number; removed: number; complete: boolean } | null;
}

export const useScan = create<ScanState>(() => ({ scanning: false, rootId: null, seen: 0, parsed: 0, error: null, last: null }));

let nextScanId = 1;
const running = new Map<string, Promise<void>>();

/** Whether a root can be scanned now: a granted handle, or files picked this session. */
async function sourceOf(rootId: string): Promise<ScanSource | null> {
  const root = await libraryRoots.get(rootId);
  if (!root) return null;
  if (root.kind === 'fsa') return root.handle && useRoots.getState().access[rootId] === 'granted' ? { kind: 'dir', handle: root.handle } : null;
  const files = sessionFiles.get(rootId);
  return files ? { kind: 'files', files: [...files].map(([relPath, file]) => ({ relPath, file })) } : null;
}

/**
 * Scans a root incrementally. One scan per root at a time in this tab, and one across tabs (a Web Lock),
 * so two scans never race on the unique `localUri`. The walk reports batches that are written as they
 * arrive, so an interrupted scan resumes where it left off (the next diff sees what is already indexed).
 */
export function scanRoot(rootId: string): Promise<void> {
  const existing = running.get(rootId);
  if (existing) return existing;
  const run = withLock(`auden-scan:${rootId}`, () => doScan(rootId)).finally(() => running.delete(rootId));
  running.set(rootId, run);
  return run;
}

async function withLock(name: string, fn: () => Promise<void>): Promise<void> {
  const locks = (typeof navigator !== 'undefined' ? navigator.locks : undefined) as LockManager | undefined;
  if (!locks) return fn();
  return locks.request(name, { ifAvailable: true }, async (lock) => {
    if (lock) await fn(); // another tab is scanning this root: nothing to do
  });
}

async function doScan(rootId: string): Promise<void> {
  const source = await sourceOf(rootId);
  if (!source) {
    useScan.setState({ error: 'The folder isn’t connected.' });
    return;
  }
  const known = await knownFiles(rootId);
  const scanId = nextScanId++;
  useScan.setState({ scanning: true, rootId, seen: 0, parsed: 0, error: null });
  const worker = new Worker(new URL('./indexer.worker.ts', import.meta.url), { type: 'module' });
  let added = 0;
  let changed = 0;
  try {
    const finished = await new Promise<Extract<ScanMessage, { type: 'done' }>>((resolve, reject) => {
      let writes: Promise<unknown> = Promise.resolve();
      worker.onmessage = (event: MessageEvent<ScanMessage>) => {
        const msg = event.data;
        if (msg.scanId !== scanId) return;
        if (msg.type === 'progress') useScan.setState({ seen: msg.seen, parsed: msg.parsed });
        else if (msg.type === 'batch') {
          writes = writes
            .then(() => applyBatch(rootId, msg.entries, msg.art))
            .then((r) => {
              added += r.added;
              changed += r.changed;
            })
            .catch(reject);
        } else writes.then(() => resolve(msg), reject);
      };
      worker.onerror = (e) => reject(new Error(e.message || 'The indexer stopped.'));
      const request: ScanRequest = { type: 'scan', scanId, source, known };
      worker.postMessage(request);
    });
    const root = await libraryRoots.get(rootId);
    const knownPaths = known.map(([path]) => path);
    // A picked folder (no handle) is tied to its root by name alone: see plausiblySameFolder.
    const sameFolder = root?.kind !== 'files' || plausiblySameFolder(knownPaths, finished.seen);
    const trusted = finished.complete && sameFolder;
    const gone = planGone(knownPaths, finished.seen, trusted);
    const removed = await dropFiles(rootId, gone);
    if (root && trusted) await libraryRoots.put({ ...root, scannedAt: Date.now() });
    useScan.setState({
      last: { added, changed, removed, complete: trusted },
      error: !finished.complete
        ? `Some files couldn’t be read (${finished.errors.length}); nothing was removed.`
        : !sameFolder
          ? 'This doesn’t look like the folder that was indexed, so nothing was removed. Use Reconnect folder on the original one, or remove it first.'
          : null,
    });
  } catch (error) {
    useScan.setState({ error: error instanceof Error ? error.message : String(error) });
  } finally {
    worker.terminate();
    useScan.setState({ scanning: false });
  }
}
