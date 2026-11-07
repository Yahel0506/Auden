import { type LyricsSourceId, sourceById } from '@/lyrics/sources';

/**
 * How a lyrics provider is written in a saved order or a download's record:
 * by LyricsSource's enum name on Android, so the two apps read each other's
 * choices. The Android-only hidden sources (SIMP_MUSIC, MEGALOBIZ) have no
 * web id and come back as null.
 */
const STORED_NAMES: Record<LyricsSourceId, string> = {
  'lrc-red': 'LRC_RED',
  bini: 'BINI_LYRICS',
  'better-lyrics': 'BETTER_LYRICS',
  'better-lyrics-portato': 'BETTER_LYRICS_PORTATO',
  paxsenix: 'PAXSENIX',
  'paxsenix-spotify': 'PAXSENIX_SPOTIFY',
  'paxsenix-musixmatch': 'PAXSENIX_MUSIXMATCH',
  'lyrics-plus': 'LYRICS_PLUS',
  unison: 'UNISON',
  'youtube-captions': 'YOUTUBE_TRANSCRIPT',
  'youtube-music': 'YOUTUBE_MUSIC',
  kugou: 'KUGOU',
  lrclib: 'LRCLIB',
  musixmatch: 'MUSIXMATCH',
  genius: 'GENIUS',
};

const IDS_BY_STORED_NAME = new Map<string, LyricsSourceId>(
  (Object.entries(STORED_NAMES) as [LyricsSourceId, string][]).map(([id, name]) => [name, id]),
);

export function toStoredProvider(id: LyricsSourceId): string {
  return STORED_NAMES[id];
}

export function fromStoredProvider(name: string): LyricsSourceId | null {
  return IDS_BY_STORED_NAME.get(name) ?? null;
}

/** "SIMP_MUSIC" reads as "Simp Music" when the web has no source of that name. */
export function storedProviderLabel(name: string): string {
  const id = fromStoredProvider(name);
  if (id) return sourceById(id)?.label ?? name;
  return name
    .split('_')
    .filter(Boolean)
    .map((word) => word[0]!.toUpperCase() + word.slice(1).toLowerCase())
    .join(' ');
}
