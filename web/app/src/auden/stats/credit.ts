/** ListeningStats.kt's name handling, so artist and album keys match the phone's buckets. */

const CREDIT_SEPARATORS = /\s*,\s*|\s+&\s+|\s+x\s+|\s+feat\.?\s+|\s+ft\.?\s+|\s+featuring\s+/i;

/** ASCII 0x1F: cannot occur in a title, so two names never collide on the join. */
const UNIT_SEPARATOR = String.fromCharCode(31);

/**
 * The lead artist of a credit. Split on punctuation and feature markers, not on " and " or
 * " with ", which are as often part of a band's name.
 */
export function primaryArtist(credit: string): string | null {
  const name = credit
    .split(CREDIT_SEPARATORS)
    .find((part) => part.trim().length > 0)
    ?.trim();
  return name ? name : null;
}

/** An album's bucket key: its id when YouTube gave one, else name and artist. */
export function albumKey(albumId: string | null, name: string, artist: string): string {
  return albumId ?? name.toLowerCase() + UNIT_SEPARATOR + artist.toLowerCase();
}
