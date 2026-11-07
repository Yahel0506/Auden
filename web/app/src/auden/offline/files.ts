/**
 * Where the audio lives: a flat folder in the browser's origin-private file system (OPFS). A file is written
 * through one writable stream and only exists, under its name, once [FileSink.close] commits it, so there is
 * no half-written file to find and no `.part` to rename (Chromium's `createWritable` swaps atomically on close).
 * A failed or cancelled write is [abort]ed and leaves nothing.
 */
export interface FileSink {
  write(chunk: Uint8Array): Promise<void>;
  close(): Promise<void>;
  abort(): Promise<void>;
}

export interface StoredFile {
  name: string;
  bytes: number;
}

export interface FileStore {
  begin(name: string): Promise<FileSink>;
  read(name: string): Promise<Blob | null>;
  remove(name: string): Promise<void>;
  list(): Promise<StoredFile[]>;
}

export const OFFLINE_DIR = 'auden-offline';

/** Whether this browser can write to OPFS the way [opfsFiles] does (Safari before 26 cannot). */
export function opfsSupported(): boolean {
  return typeof navigator !== 'undefined' && !!navigator.storage?.getDirectory && typeof FileSystemFileHandle !== 'undefined' && 'createWritable' in FileSystemFileHandle.prototype;
}

export function opfsFiles(): FileStore {
  const dir = async (): Promise<FileSystemDirectoryHandle> => (await navigator.storage.getDirectory()).getDirectoryHandle(OFFLINE_DIR, { create: true });
  return {
    async begin(name) {
      const handle = await (await dir()).getFileHandle(name, { create: true });
      const stream = await handle.createWritable();
      return {
        write: (chunk) => stream.write(chunk as Uint8Array<ArrayBuffer>),
        close: () => stream.close(),
        abort: async () => {
          await stream.abort().catch(() => undefined);
          // A brand-new empty entry would otherwise stay behind; one that already held a copy keeps it.
          const file = await handle.getFile().catch(() => null);
          if (file && file.size === 0) await (await dir()).removeEntry(name).catch(() => undefined);
        },
      };
    },
    async read(name) {
      try {
        return await (await (await dir()).getFileHandle(name)).getFile();
      } catch {
        return null;
      }
    },
    async remove(name) {
      try {
        await (await dir()).removeEntry(name);
      } catch {
        /* already gone */
      }
    },
    async list() {
      const out: StoredFile[] = [];
      const folder = await dir();
      // FileSystemDirectoryHandle.entries() is async-iterable in every browser with OPFS.
      for await (const [name, handle] of (folder as unknown as { entries(): AsyncIterable<[string, FileSystemHandle]> }).entries()) {
        if (handle.kind === 'file') out.push({ name, bytes: (await (handle as FileSystemFileHandle).getFile()).size });
      }
      return out;
    },
  };
}

/** An in-memory [FileStore], for tests and as the fake the queue is exercised against. */
export function memoryFiles(): FileStore & { data: Map<string, Uint8Array> } {
  const data = new Map<string, Uint8Array>();
  return {
    data,
    async begin(name) {
      const parts: Uint8Array[] = [];
      let done = false;
      return {
        async write(chunk) {
          if (done) throw new Error('closed');
          parts.push(chunk.slice());
        },
        async close() {
          done = true;
          const out = new Uint8Array(parts.reduce((n, p) => n + p.byteLength, 0));
          let at = 0;
          for (const p of parts) (out.set(p, at), (at += p.byteLength));
          data.set(name, out);
        },
        async abort() {
          done = true;
        },
      };
    },
    async read(name) {
      const bytes = data.get(name);
      return bytes ? new Blob([bytes as Uint8Array<ArrayBuffer>]) : null;
    },
    async remove(name) {
      data.delete(name);
    },
    async list() {
      return [...data].map(([name, bytes]) => ({ name, bytes: bytes.byteLength }));
    },
  };
}
