// Port of dev/auden/library/ident/YoutubeIdInName.kt.

const ID_CHARS = /^[A-Za-z0-9_-]{11}$/;
const BRACKETED = /\[([A-Za-z0-9_-]{11})\]\.[A-Za-z0-9]{2,5}$/;
const URL_FORM = /(?:v=|youtu\.be\/)([A-Za-z0-9_-]{11})(?![A-Za-z0-9_-])/;

/** An 11-character id encodes 66 bits in 64, so its last character carries only four of its six. */
const LAST_CHARS = 'AEIMQUYcgkosw048';

const WORD_LIKE = /^(?:[A-Za-z][a-z]*|[A-Z]+|[0-9]+)$/;
const COUNTER = /^(?:[A-Za-z]+[0-9]+|[0-9]+[A-Za-z]+)$/;

function plausible(id: string): boolean {
  return ID_CHARS.test(id) && LAST_CHARS.includes(id.charAt(id.length - 1));
}

function looksLikeText(id: string): boolean {
  if (COUNTER.test(id)) return true;
  const parts = id.split(/[_-]/).filter((p) => p.length > 0);
  // A real id nearly always has a digit or a capital in the middle of a word; text has neither.
  return parts.length > 0 && parts.every((p) => WORD_LIKE.test(p));
}

/**
 * Reads a YouTube video id out of a file name: a `v=` / `youtu.be/` form anywhere in it,
 * or an 11-character bracket that is the last thing before the extension.
 */
export function extractYoutubeId(fileName: string): string | null {
  const url = URL_FORM.exec(fileName)?.[1];
  if (url !== undefined && plausible(url)) return url;
  const id = BRACKETED.exec(fileName)?.[1];
  if (id === undefined) return null;
  return plausible(id) && !looksLikeText(id) ? id : null;
}
