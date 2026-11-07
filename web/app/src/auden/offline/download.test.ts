import { describe, expect, it } from 'vitest';
import { downloadToFile, looksLikeAudio, type AudioStream } from './download';
import { memoryFiles } from './files';

const WEBM = [0x1a, 0x45, 0xdf, 0xa3];
const chunkOf = (n: number, head = WEBM) => Uint8Array.from({ length: n }, (_, i) => head[i] ?? i % 256);
const ctx = () => ({ signal: new AbortController().signal, progress: () => {} });

/** A stream that serves [total] bytes in [size]-byte chunks. */
function serve(total: number, size: number, mime = 'audio/webm', declared = total): AudioStream {
  return async (_id, { onChunk }) => {
    const info = { itag: 251, mime, total: declared, musicVideo: false, modified: '1' };
    for (let at = 0; at < total; at += size) await onChunk(chunkOf(Math.min(size, total - at), at === 0 ? WEBM : []), at, info);
    return info;
  };
}

describe('looksLikeAudio', () => {
  it('knows a WebM, an MP4 and markup', () => {
    expect(looksLikeAudio('audio/webm; codecs="opus"', Uint8Array.from(WEBM))).toBe(true);
    expect(looksLikeAudio('audio/webm', new TextEncoder().encode('<html>'))).toBe(false);
    expect(looksLikeAudio('audio/mp4', Uint8Array.from([0, 0, 0, 24, 102, 116, 121, 112]))).toBe(true);
    expect(looksLikeAudio('audio/mp4', new TextEncoder().encode('<!doctype'))).toBe(false);
  });
});

describe('downloadToFile', () => {
  it('writes every chunk under the video id, reporting progress', async () => {
    const files = memoryFiles();
    const seen: number[] = [];
    const result = await downloadToFile({ videoId: 'abcdefghijk' }, files, { signal: new AbortController().signal, progress: (d) => seen.push(d) }, serve(2500, 1000));
    expect(result).toEqual({ file: 'abcdefghijk.webm', mime: 'audio/webm', bytes: 2500, itag: 251 });
    expect(files.data.get('abcdefghijk.webm')!.byteLength).toBe(2500);
    expect(seen).toEqual([1000, 2000, 2500]);
  });

  it('keeps nothing when the byte count is not what the server declared', async () => {
    const files = memoryFiles();
    await expect(downloadToFile({ videoId: 'abcdefghijk' }, files, ctx(), serve(2000, 1000, 'audio/webm', 5000))).rejects.toThrow(/2000 of 5000/);
    expect(files.data.size).toBe(0);
  });

  it('refuses a 200 that is not audio, and writes nothing', async () => {
    const files = memoryFiles();
    const html: AudioStream = async (_id, { onChunk }) => {
      const info = { itag: 251, mime: 'audio/webm', total: 20, musicVideo: false, modified: null };
      await onChunk(new TextEncoder().encode('<html>blocked</html>'), 0, info);
      return info;
    };
    await expect(downloadToFile({ videoId: 'abcdefghijk' }, files, ctx(), html)).rejects.toThrow(/not audio/);
    expect(files.data.size).toBe(0);
  });

  it('aborts the write when the stream fails half-way', async () => {
    const files = memoryFiles();
    const broken: AudioStream = async (_id, { onChunk }) => {
      const info = { itag: 251, mime: 'audio/webm', total: 3000, musicVideo: false, modified: null };
      await onChunk(chunkOf(1000), 0, info);
      throw new TypeError('network lost');
    };
    await expect(downloadToFile({ videoId: 'abcdefghijk' }, files, ctx(), broken)).rejects.toThrow('network lost');
    expect(files.data.size).toBe(0);
  });

  it('stops on abort', async () => {
    const files = memoryFiles();
    const ctl = new AbortController();
    const stream: AudioStream = async (_id, { onChunk }) => {
      const info = { itag: 251, mime: 'audio/webm', total: 3000, musicVideo: false, modified: null };
      await onChunk(chunkOf(1000), 0, info);
      ctl.abort();
      await onChunk(chunkOf(1000, []), 1000, info);
      return info;
    };
    await expect(downloadToFile({ videoId: 'abcdefghijk' }, files, { signal: ctl.signal, progress: () => {} }, stream)).rejects.toMatchObject({ name: 'AbortError' });
    expect(files.data.size).toBe(0);
  });
});
