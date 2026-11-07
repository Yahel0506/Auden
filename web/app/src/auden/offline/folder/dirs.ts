/**
 * The slice of the File System Access directory API the folder writer needs, so a real handle (the user's folder, or
 * an OPFS directory in the browser exercise) and the in-memory fake below are interchangeable.
 */
export interface WritableLike {
  write(chunk: Uint8Array): Promise<void>;
  close(): Promise<void>;
  abort(): Promise<void>;
}

export interface FileHandleLike {
  createWritable(): Promise<WritableLike>;
  getFile(): Promise<{ size: number }>;
}

export interface DirHandleLike {
  readonly name: string;
  getDirectoryHandle(name: string, options?: { create?: boolean }): Promise<DirHandleLike>;
  getFileHandle(name: string, options?: { create?: boolean }): Promise<FileHandleLike>;
  removeEntry(name: string): Promise<void>;
}

/** A real handle seen through the slice (the DOM types ask for BufferSource where the writer passes bytes). */
export const asDir = (handle: FileSystemDirectoryHandle): DirHandleLike => handle as unknown as DirHandleLike;

const domError = (name: string, message = name) => new DOMException(message, name);

/** An in-memory directory tree, with the failure modes the writer must survive. */
export interface MemoryDir extends DirHandleLike {
  /** Every file below, as `path → bytes`, for assertions. */
  files(): Map<string, Uint8Array>;
  /** Makes the next write to a file with this name fail with a DOMException of this name (once). */
  failWrite(fileName: string, errorName: string): void;
  /** Makes `createWritable` of a file with this name fail with a DOMException of this name (once). */
  failCreate(fileName: string, errorName: string): void;
  /** Every operation fails with this error from now on (permission revoked), or null to clear. */
  revoke(errorName: string | null): void;
  /** Pre-creates a file (a user's own file in the way). */
  put(path: string, bytes: Uint8Array): void;
  /** The permission the browser would report (and what a request turns into, when it is asked). */
  permission: PermissionState;
  /** What `requestPermission` answers; default 'granted'. */
  willGrant: PermissionState;
  requests: number;
  queryPermission(d: { mode: 'readwrite' }): Promise<PermissionState>;
  requestPermission(d: { mode: 'readwrite' }): Promise<PermissionState>;
  entries(): AsyncIterator<[string, unknown]>;
  isSameEntry(other: unknown): Promise<boolean>;
  resolve(other: unknown): Promise<string[] | null>;
}

interface Node {
  kind: 'dir' | 'file';
  name: string;
  children?: Map<string, Node>;
  bytes?: Uint8Array;
}

const keyOf = (name: string, insensitive: boolean) => (insensitive ? name.normalize('NFC').toLowerCase() : name);

export function memoryDir(options: { caseInsensitive?: boolean } = {}): MemoryDir {
  const insensitive = !!options.caseInsensitive;
  const root: Node = { kind: 'dir', name: 'chosen', children: new Map() };
  const write: Record<string, string> = {};
  const create: Record<string, string> = {};
  let revoked: string | null = null;
  const guard = () => {
    if (revoked) throw domError(revoked);
  };

  const dirHandle = (node: Node): DirHandleLike => ({
    name: node.name,
    async getDirectoryHandle(name, opts) {
      guard();
      const key = keyOf(name, insensitive);
      const existing = node.children!.get(key);
      if (existing) {
        if (existing.kind !== 'dir') throw domError('TypeMismatchError');
        return dirHandle(existing);
      }
      if (!opts?.create) throw domError('NotFoundError');
      const made: Node = { kind: 'dir', name, children: new Map() };
      node.children!.set(key, made);
      return dirHandle(made);
    },
    async getFileHandle(name, opts) {
      guard();
      const key = keyOf(name, insensitive);
      let file = node.children!.get(key);
      if (file && file.kind !== 'file') throw domError('TypeMismatchError');
      if (!file) {
        if (!opts?.create) throw domError('NotFoundError');
        file = { kind: 'file', name, bytes: new Uint8Array(0) };
        node.children!.set(key, file);
      }
      const target = file;
      return {
        async getFile() {
          guard();
          return { size: target.bytes!.byteLength };
        },
        async createWritable() {
          guard();
          const failCreate = create[name];
          if (failCreate) {
            delete create[name];
            throw domError(failCreate);
          }
          const parts: Uint8Array[] = [];
          return {
            async write(chunk) {
              guard();
              const fail = write[name];
              if (fail) {
                delete write[name];
                throw domError(fail);
              }
              parts.push(chunk.slice());
            },
            async close() {
              guard();
              const out = new Uint8Array(parts.reduce((n, p) => n + p.byteLength, 0));
              let at = 0;
              for (const p of parts) (out.set(p, at), (at += p.byteLength));
              target.bytes = out;
            },
            async abort() {
              parts.length = 0;
            },
          };
        },
      };
    },
    async removeEntry(name) {
      guard();
      if (!node.children!.delete(keyOf(name, insensitive))) throw domError('NotFoundError');
    },
  });

  const walk = (node: Node, prefix: string, out: Map<string, Uint8Array>) => {
    for (const child of node.children!.values()) {
      const path = prefix ? `${prefix}/${child.name}` : child.name;
      if (child.kind === 'dir') walk(child, path, out);
      else out.set(path, child.bytes!);
    }
  };

  const self: MemoryDir = {
    ...dirHandle(root),
    permission: 'granted',
    willGrant: 'granted',
    requests: 0,
    async queryPermission() {
      return self.permission;
    },
    async requestPermission() {
      self.requests++;
      if (self.permission !== 'denied') self.permission = self.willGrant;
      return self.permission;
    },
    async *entries() {
      guard();
      for (const child of root.children!.values()) yield [child.name, child] as [string, unknown];
    },
    async isSameEntry(other) {
      return other === self;
    },
    async resolve() {
      return null;
    },
    files() {
      const out = new Map<string, Uint8Array>();
      walk(root, '', out);
      return out;
    },
    failWrite(fileName, errorName) {
      write[fileName] = errorName;
    },
    failCreate(fileName, errorName) {
      create[fileName] = errorName;
    },
    revoke(errorName) {
      revoked = errorName;
    },
    put(path, bytes) {
      const parts = path.split('/');
      let dir = root;
      for (const part of parts.slice(0, -1)) {
        const key = keyOf(part, insensitive);
        let next = dir.children!.get(key);
        if (!next) dir.children!.set(key, (next = { kind: 'dir', name: part, children: new Map() }));
        dir = next;
      }
      const name = parts[parts.length - 1]!;
      dir.children!.set(keyOf(name, insensitive), { kind: 'file', name, bytes });
    },
  };
  return self;
}
