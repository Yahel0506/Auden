import { t as translateUi } from '@/i18n';

/**
 * The lyric providers, in default priority: LyricsSource.kt's declaration
 * order, minus its hidden entries (SimpMusic and Megalobiz). The user can
 * reorder and switch them off, as on the desktop (useLyricsSettings).
 */
export const LYRICS_SOURCES = [
  { id: 'lrc-red', label: 'lrc.red', detailKey: 'lyrics_source_lrc_red', wordSynced: true },
  { id: 'bini', label: 'BiniLyrics', detailKey: 'lyrics_source_bini', wordSynced: true },
  { id: 'better-lyrics', label: 'BetterLyrics', detailKey: 'lyrics_source_better_lyrics', wordSynced: true },
  { id: 'better-lyrics-portato', label: 'BetterLyrics Portato', detailKey: 'lyrics_source_better_lyrics_portato', wordSynced: true },
  { id: 'paxsenix', label: 'PaxSenix', detailKey: 'lyrics_source_paxsenix', wordSynced: true },
  { id: 'paxsenix-spotify', label: 'PaxSenix: Spotify', detailKey: 'lyrics_source_paxsenix_spotify', wordSynced: false },
  { id: 'paxsenix-musixmatch', label: 'PaxSenix: Musixmatch', detailKey: 'lyrics_source_paxsenix_musixmatch', wordSynced: true },
  { id: 'lyrics-plus', label: 'LyricsPlus', detailKey: 'lyrics_source_lyrics_plus', wordSynced: true },
  { id: 'unison', label: 'Unison', detailKey: 'lyrics_source_unison', wordSynced: true },
  { id: 'youtube-captions', label: 'YouTube captions', labelKey: 'lyrics_source.youtube_captions_label', detailKey: 'lyrics_source_youtube_captions', wordSynced: false },
  { id: 'youtube-music', label: 'YouTube Music', detailKey: 'lyrics_source_youtube_music', wordSynced: false },
  { id: 'kugou', label: 'KuGou', detailKey: 'lyrics_source_kugou', wordSynced: false },
  { id: 'lrclib', label: 'LRCLIB', detailKey: 'lyrics_source_lrclib', wordSynced: false },
  { id: 'musixmatch', label: 'Musixmatch', detailKey: 'lyrics_source_musixmatch', wordSynced: false },
  { id: 'genius', label: 'Genius', detailKey: 'lyrics_source_genius', wordSynced: false },
] as const;

export type LyricsSourceId = (typeof LYRICS_SOURCES)[number]['id'];
export type LyricsSource = (typeof LYRICS_SOURCES)[number];

export const sourceById = (id: string) => LYRICS_SOURCES.find((s) => s.id === id);
export const sourceLabel = (source: LyricsSource | undefined): string => !source ? '' : 'labelKey' in source ? translateUi(source.labelKey) : source.label;

/**
 * A saved order brought up to date with this build (LyricsSource.ordered):
 * unknown ids dropped, and a source added since slotted in right after the
 * one it's declared behind, so it lands where a fresh install puts it.
 */
export function orderedSources(saved: readonly string[]): LyricsSourceId[] {
  const offered = LYRICS_SOURCES.map((s) => s.id);
  const result = [...new Set(saved)].filter((id): id is LyricsSourceId => (offered as readonly string[]).includes(id));
  offered.forEach((id, index) => {
    if (result.includes(id)) return;
    const before = offered.slice(0, index).reverse().find((b) => result.includes(b));
    result.splice(before ? result.indexOf(before) + 1 : 0, 0, id);
  });
  return result;
}

// ---- LyricsQuery.kt -------------------------------------------------------------------

const CREDITS = [
  /\s*[([]\s*(feat|ft|featuring|with)\b[^)\]]*[)\]]/gi,
  /\s+(feat|ft|featuring)\.?\s+.*$/gi,
  /\s*[([]\s*(official\s*)?(music\s*)?(video|audio|visuali[sz]er|lyrics?\s*video|lyrics?|m\/?v|hd|hq|4k|full\s*song)\s*[)\]]/gi,
  /\s*[([]\s*official\s*[)\]]/gi,
];

/** A YouTube title as a lyrics database indexed it: credits and packaging off, version words kept. */
export function forLyricsSearch(title: string): string {
  let name = title;
  for (const pattern of CREDITS) name = name.replace(pattern, ' ');
  const cleaned = name.replace(/\s+/g, ' ').trim().replace(/[,\-–—]+$/, '').trim();
  return cleaned || title.trim();
}

/** " - Topic" off an auto-generated channel name. */
export function artistForLyricsSearch(artist: string): string {
  return artist.replace(/ - Topic$/, '').trim() || artist.trim();
}
