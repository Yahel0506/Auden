/// <reference lib="webworker" />
import { parseBlob } from 'music-metadata';
import { artKeyOf, isAudioName, needsParse } from './diff';
import type { ParsedTags, ScanArt, ScanEntry, ScanMessage, ScanRequest } from './protocol';

/**
 * Walks a folder and reads tags for the files that are new or changed. It never writes to the database
 * and never asks for permission (no user gesture here): the page does both. Cancelling is a message.
 */

const BATCH = 25;
const MAX_COVER_PX = 256;
const cancelled = new Set<number>();

self.onmessage = (event: MessageEvent<ScanRequest | { type: 'cancel'; scanId: number }>) => {
  const msg = event.data;
  if (msg.type === 'cancel') cancelled.add(msg.scanId);
  else {
    // An unexpected failure must still end the scan, or the page waits (and holds the scan lock) forever.
    scan(msg).catch((error: unknown) =>
      post({ type: 'done', scanId: msg.scanId, seen: [], complete: false, errors: [error instanceof Error ? error.message : String(error)] }),
    );
  }
};

function post(message: ScanMessage): void {
  (self as unknown as Worker).postMessage(message);
}

interface Found {
  relPath: string;
  file: () => Promise<File>;
  /** Known without reading the file, when the source has the File already. */
  stat?: { size: number; modifiedAt: number };
}

async function* walk(handle: FileSystemDirectoryHandle, prefix: string, errors: string[]): AsyncGenerator<Found> {
  try {
    // entries() is in the File System Access spec; lib.dom in this TS version may not type it.
    const iterator = (handle as unknown as { entries(): AsyncIterable<[string, FileSystemHandle]> }).entries();
    for await (const [name, child] of iterator) {
      if (child.kind === 'directory') {
        yield* walk(child as FileSystemDirectoryHandle, prefix + name + '/', errors);
      } else if (isAudioName(name)) {
        const fileHandle = child as FileSystemFileHandle;
        yield { relPath: prefix + name, file: () => fileHandle.getFile() };
      }
    }
  } catch (error) {
    errors.push(`${prefix || '/'}: ${error instanceof Error ? error.name : String(error)}`);
  }
}

async function* fromFiles(files: Array<{ relPath: string; file: File }>): AsyncGenerator<Found> {
  for (const { relPath, file } of files) {
    if (!isAudioName(file.name)) continue;
    yield { relPath, file: () => Promise.resolve(file), stat: { size: file.size, modifiedAt: file.lastModified } };
  }
}

async function scan(req: ScanRequest): Promise<void> {
  const known = new Map(req.known.map(([path, size, modifiedAt]) => [path, { size, modifiedAt }]));
  const seen: string[] = [];
  const errors: string[] = [];
  const postedArt = new Set<string>();
  let entries: ScanEntry[] = [];
  let art: ScanArt[] = [];
  let parsed = 0;
  const flush = () => {
    if (entries.length) post({ type: 'batch', scanId: req.scanId, entries, art });
    entries = [];
    art = [];
    post({ type: 'progress', scanId: req.scanId, seen: seen.length, parsed });
  };
  const source = req.source.kind === 'dir' ? walk(req.source.handle, '', errors) : fromFiles(req.source.files);
  for await (const found of source) {
    if (cancelled.has(req.scanId)) {
      cancelled.delete(req.scanId);
      // A cancelled walk is incomplete by definition: nothing may be removed because of it.
      post({ type: 'done', scanId: req.scanId, seen, complete: false, errors: ['cancelled'] });
      return;
    }
    seen.push(found.relPath);
    try {
      const file = await found.file();
      const size = found.stat?.size ?? file.size;
      const modifiedAt = found.stat?.modifiedAt ?? file.lastModified;
      if (!needsParse(known.get(found.relPath), size, modifiedAt)) continue;
      const { tags, cover } = await readTags(file);
      const artKey = artKeyOf(tags, found.relPath);
      if (cover && !postedArt.has(artKey)) {
        postedArt.add(artKey);
        art.push({ key: artKey, bytes: cover.bytes, mime: cover.mime });
      }
      entries.push({ relPath: found.relPath, size, modifiedAt, tags, artKey });
      parsed++;
    } catch (error) {
      // A file that cannot be read (changed under us, NotReadableError) is skipped, and the walk is not complete.
      errors.push(`${found.relPath}: ${error instanceof Error ? error.name : String(error)}`);
    }
    if (entries.length >= BATCH) flush();
    else if (seen.length % 200 === 0) post({ type: 'progress', scanId: req.scanId, seen: seen.length, parsed });
  }
  flush();
  post({ type: 'done', scanId: req.scanId, seen, complete: errors.length === 0, errors });
}

async function readTags(file: File): Promise<{ tags: ParsedTags | null; cover: { bytes: ArrayBuffer; mime: string } | null }> {
  try {
    const meta = await parseBlob(file, { skipPostHeaders: true });
    const { common, format } = meta;
    const tags: ParsedTags = {
      title: common.title ?? null,
      artist: common.artist ?? (common.artists?.length ? common.artists.join(', ') : null),
      album: common.album ?? null,
      albumArtist: common.albumartist ?? null,
      genre: common.genre?.[0] ?? null,
      year: common.year ?? null,
      trackNumber: common.track?.no ?? null,
      discNumber: common.disk?.no ?? null,
      durationSec: format.duration ?? null,
    };
    const picture = common.picture?.[0];
    const cover = picture ? await shrinkCover(picture.data, picture.format) : null;
    return { tags, cover };
  } catch {
    // Unreadable tags still leave a playable file: it is indexed under its file name.
    return { tags: null, cover: null };
  }
}

/** ≤256 px JPEG when the browser can decode it in a Worker; otherwise the original if it is small, else none. */
async function shrinkCover(data: Uint8Array, mime: string): Promise<{ bytes: ArrayBuffer; mime: string } | null> {
  const copy = data.slice().buffer as ArrayBuffer;
  try {
    const bitmap = await createImageBitmap(new Blob([copy], { type: mime }));
    const scale = Math.min(1, MAX_COVER_PX / Math.max(bitmap.width, bitmap.height));
    const canvas = new OffscreenCanvas(Math.max(1, Math.round(bitmap.width * scale)), Math.max(1, Math.round(bitmap.height * scale)));
    canvas.getContext('2d')!.drawImage(bitmap, 0, 0, canvas.width, canvas.height);
    bitmap.close();
    const blob = await canvas.convertToBlob({ type: 'image/jpeg', quality: 0.82 });
    return { bytes: await blob.arrayBuffer(), mime: 'image/jpeg' };
  } catch {
    return copy.byteLength <= 150_000 ? { bytes: copy, mime } : null;
  }
}
