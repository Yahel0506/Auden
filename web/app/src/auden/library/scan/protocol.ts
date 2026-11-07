/** Messages between the indexer (main thread) and its Worker. Everything here is structured-cloneable. */

export interface ParsedTags {
  title: string | null;
  artist: string | null;
  album: string | null;
  albumArtist: string | null;
  genre: string | null;
  year: number | null;
  trackNumber: number | null;
  discNumber: number | null;
  durationSec: number | null;
}

export interface ScanEntry {
  relPath: string;
  size: number;
  modifiedAt: number;
  /** Null when the tags could not be read: the file is still indexed, named after its file name. */
  tags: ParsedTags | null;
  /** Key of the cover in `local_art` (the cover itself travels in the batch's `art`). */
  artKey: string | null;
}

export interface ScanArt {
  key: string;
  bytes: ArrayBuffer;
  mime: string;
}

export type ScanSource =
  | { kind: 'dir'; handle: FileSystemDirectoryHandle }
  /**
   * Firefox: files from `<input webkitdirectory>`, already keyed by their path inside the folder (the main thread
   * reads `webkitRelativePath`; it is not relied on to survive being posted to a Worker).
   */
  | { kind: 'files'; files: Array<{ relPath: string; file: File }> };

export interface ScanRequest {
  type: 'scan';
  scanId: number;
  source: ScanSource;
  /** What the index already holds for this root: [relPath, size, modifiedAt]. Unchanged files are not parsed again. */
  known: Array<[string, number, number]>;
}

export type ScanMessage =
  | { type: 'progress'; scanId: number; seen: number; parsed: number }
  | { type: 'batch'; scanId: number; entries: ScanEntry[]; art: ScanArt[] }
  | {
      type: 'done';
      scanId: number;
      /** Every audio file the walk saw. */
      seen: string[];
      /** False when any folder or file could not be walked: nothing may be treated as gone then. */
      complete: boolean;
      errors: string[];
    };
