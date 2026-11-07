import { useQuery } from '@tanstack/react-query';
import { useLyricsUi } from '@/lyrics/useLyrics';
import type { LyricsResult, LyricsTrack } from '@/lyrics/repository';
import { clampOffset, useLyricsSettings } from '@/lyrics/settings';
import type { LyricsSourceId } from '@/lyrics/sources';
import { audenKey } from '../db/queries';
import { keepExtras } from './batch';
import { lyricsKeyFor } from './key';
import { toLrc } from './lrc';
import { effectiveOffset } from './offset';
import { readPref, removeVariant, saveVariant, setActiveProvider, setSongOffset } from './store';

/** The panel's lyric actions, over the saved-lyrics store. */

/** The provider the song shows from now on (null = "Best available"); remembered across visits. */
export async function chooseProvider(videoId: string, id: LyricsSourceId | null): Promise<void> {
  useLyricsUi.getState().pick(videoId, id);
  await setActiveProvider(await lyricsKeyFor(videoId), id);
}

/** Keeps the lyrics on screen as the song's chosen ones (and the extras the listener asked for). */
export async function saveLyrics(track: LyricsTrack, result: LyricsResult): Promise<void> {
  const key = await lyricsKeyFor(track.videoId);
  await saveVariant(key, result, { makeActive: true });
  void keepExtras(key, track, result.source);
}

/** Makes an already saved variant the one shown. */
export async function selectSavedVariant(videoId: string, provider: LyricsSourceId): Promise<void> {
  useLyricsUi.getState().pick(videoId, null);
  await setActiveProvider(await lyricsKeyFor(videoId), provider);
}

export async function deleteSavedVariant(videoId: string, provider: string): Promise<void> {
  await removeVariant(await lyricsKeyFor(videoId), provider);
}

// ---- Offset ----------------------------------------------------------------------------

export interface LyricsOffset {
  /** What is applied: the song's own, else the global. */
  effective: number;
  /** The song's own offset; null when it follows the global one. */
  own: number | null;
  global: number;
  /** "This song" mode: the song has its own offset. */
  perSong: boolean;
}

/** The offset the lyrics of [songKey] play with, live. */
export function useLyricsOffset(songKey: string | null): LyricsOffset {
  const global = useLyricsSettings((s) => s.lyricsOffsetMs);
  const pref = useQuery({ queryKey: audenKey('song_lyrics_pref', songKey), queryFn: () => readPref(songKey!), enabled: !!songKey, staleTime: Infinity });
  const own = pref.data?.offsetMs ?? null;
  return { effective: effectiveOffset(own, global), own, global, perSong: own !== null };
}

/** Moves the offset the sheet is editing: this song's, or the global one. */
export async function setOffset(songKey: string | null, perSong: boolean, ms: number): Promise<void> {
  const value = clampOffset(ms);
  if (perSong && songKey) await setSongOffset(songKey, value);
  else useLyricsSettings.getState().setLyricsOffsetMs(value);
}

/** Switches between "This song" (seeded from what is applied now) and "All songs" (the song's own cleared). */
export async function setPerSong(songKey: string, on: boolean, current: number): Promise<void> {
  await setSongOffset(songKey, on ? clampOffset(current) : null);
}

// ---- Export ----------------------------------------------------------------------------

/** Offers [text] as a file download. */
export function downloadText(filename: string, text: string): void {
  const url = URL.createObjectURL(new Blob([text], { type: 'text/plain;charset=utf-8' }));
  const a = document.createElement('a');
  a.href = url;
  a.download = filename;
  document.body.appendChild(a);
  a.click();
  a.remove();
  setTimeout(() => URL.revokeObjectURL(url), 10_000);
}

const safeName = (s: string) => s.replace(/[\\/:*?"<>|]+/g, ' ').replace(/\s+/g, ' ').trim() || 'lyrics';

/** The lyrics as a .lrc file, with the offset in force written as the format's own [offset:] tag. */
export function exportLrc(result: LyricsResult, meta: { title: string; artist: string }, offsetMs: number): void {
  downloadText(`${safeName(`${meta.artist} - ${meta.title}`)}.lrc`, toLrc(result.lines, offsetMs, meta));
}
