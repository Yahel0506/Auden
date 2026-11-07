import type { AudioStreamInfo, StreamOptions } from '@/playback/automix/fetchAudio';
import type { FileStore, FileSink } from './files';
import type { OfflineSong } from './types';

export interface DownloadContext {
  signal: AbortSignal;
  progress: (done: number, total: number) => void;
}

export interface DownloadResult {
  file: string;
  mime: string;
  bytes: number;
  itag: number;
}

/** The byte source: [streamTrackAudio] (the player's own direct URL), or a stand-in for tests and the dev stub. */
export type AudioStream = (videoId: string, options: StreamOptions) => Promise<AudioStreamInfo>;

/** The player's own byte source, loaded on first use: it brings YouTube.js and BotGuard with it. */
const youtubeStream: AudioStream = (videoId, options) => import('@/playback/automix/fetchAudio').then((m) => m.streamTrackAudio(videoId, options));

/** A response that is not audio (a 200 with an HTML body, an error page) must not be kept as one. */
export function looksLikeAudio(mime: string, head: Uint8Array): boolean {
  if (mime.startsWith('audio/webm')) return head[0] === 0x1a && head[1] === 0x45 && head[2] === 0xdf && head[3] === 0xa3;
  if (mime.startsWith('audio/mp4')) return head.length >= 8 && String.fromCharCode(head[4]!, head[5]!, head[6]!, head[7]!) === 'ftyp';
  if (mime.startsWith('audio/')) return head.length > 0 && head[0] !== 0x3c; // anything else: at least not markup
  return false;
}

const extensionOf = (mime: string) => (mime.startsWith('audio/webm') ? 'webm' : mime.startsWith('audio/mp4') ? 'm4a' : 'bin');

/**
 * Streams the song's audio into one file in [files]. The bytes are committed under the file's name only when
 * all of them arrived, and their count equals what the server said the file is: otherwise the write is aborted
 * and nothing is left. (A resume after a refused URL is [streamTrackAudio]'s; across a restart a job starts over.)
 */
export async function downloadToFile(
  song: Pick<OfflineSong, 'videoId'>,
  files: FileStore,
  { signal, progress }: DownloadContext,
  stream: AudioStream = youtubeStream,
): Promise<DownloadResult> {
  let sink: FileSink | null = null;
  let name = '';
  let bytes = 0;
  try {
    const info = await stream(song.videoId, {
      signal,
      directClientOnly: true,
      onChunk: async (chunk, at, meta) => {
        if (signal.aborted) throw new DOMException('Aborted', 'AbortError');
        if (!sink) {
          if (!looksLikeAudio(meta.mime, chunk)) throw new Error('YouTube answered with something that is not audio');
          name = `${song.videoId}.${extensionOf(meta.mime)}`;
          sink = await files.begin(name);
        }
        if (at !== bytes) throw new Error('the audio arrived out of order');
        await sink.write(chunk);
        bytes += chunk.byteLength;
        progress(bytes, meta.total);
      },
    });
    if (!sink) throw new Error('YouTube sent no audio');
    if (info.total > 0 && bytes !== info.total) throw new Error(`the download stopped at ${bytes} of ${info.total} bytes`);
    await (sink as FileSink).close();
    sink = null;
    return { file: name, mime: info.mime, bytes, itag: info.itag };
  } catch (error) {
    await (sink as FileSink | null)?.abort().catch(() => undefined);
    throw error;
  }
}
