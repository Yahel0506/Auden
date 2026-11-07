/** Which videoIds have an offline copy, in memory (filled by runtime.ts). Kept apart so Home and the DJ can ask without loading the downloader. */
export interface KeptFile {
  file: string;
  mime: string;
}

const kept = new Map<string, KeptFile>();

export const hasOfflineCopy = (videoId: string): boolean => kept.has(videoId);
export const offlineFileOf = (videoId: string): KeptFile | undefined => kept.get(videoId);

export function replaceKept(entries: Iterable<[string, KeptFile]>): void {
  kept.clear();
  for (const [id, file] of entries) kept.set(id, file);
}
