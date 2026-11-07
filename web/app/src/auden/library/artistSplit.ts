// Port of com/music/bitchord/data/ArtistSplitter.kt.

/** Separators that join two or more artists in a credit. Matched case-insensitively. */
export const ARTIST_SEPARATORS = ['&', ',', ';', '//', ' ft. ', ' feat. ', ' featuring ', ' x '];

/** Names that contain a separator but are one artist; never taken apart. */
export const ARTIST_KEEP_WHOLE = [
  'Simon & Garfunkel', 'Earth, Wind & Fire', 'Crosby, Stills, Nash & Young',
  'Tyler, The Creator', 'Hall & Oates', 'Florence & The Machine', 'Brooks & Dunn',
  'Ike & Tina Turner', 'Sly & The Family Stone', 'Mumford & Sons', 'Of Monsters and Men',
];

const PLACEHOLDER_RE = /(\d+)/g;

function escapeRegExp(s: string): string {
  return s.replace(/[.*+?^${}()|[\]\\\/]/g, '\\$&');
}

/**
 * Splits a credit like "Artist A & Artist B, Artist C" into the artists it names.
 * Keep-whole names are swapped for placeholders, split around, and swapped back.
 * Result is trimmed, has no empties, and is de-duplicated case-insensitively (first kept).
 */
export function splitArtists(raw: string): string[] {
  const text = raw.trim();
  if (text === '') return [];

  const held: string[] = [];
  let work = text;
  for (const name of ARTIST_KEEP_WHOLE) {
    const at = work.search(new RegExp(escapeRegExp(name), 'i'));
    if (at >= 0) {
      held.push(work.substring(at, at + name.length));
      work = work.slice(0, at) + `${held.length - 1}` + work.slice(at + name.length);
    }
  }

  const pattern = new RegExp(
    [...ARTIST_SEPARATORS].sort((a, b) => b.length - a.length).map(escapeRegExp).join('|'),
    'i',
  );
  const seen = new Set<string>();
  const out: string[] = [];
  for (const part of work.split(pattern)) {
    const restored = part
      .replace(PLACEHOLDER_RE, (_, i: string) => held[Number(i)] ?? '')
      .trim();
    if (restored === '') continue;
    const key = restored.toLowerCase();
    if (seen.has(key)) continue;
    seen.add(key);
    out.push(restored);
  }
  return out;
}
