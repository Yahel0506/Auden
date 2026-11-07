/** HomeComposer.kt's rules: how YouTube Music's shelves and the local shelves share Home. */

import type { LocalShelf, LocalShelfKind } from './shelves';

/** The default share of Home that local shelves take. */
export const DEFAULT_WEIGHT = 45;

/** From this weight up, Home is mostly the listener's own and it opens on a local shelf. */
export const LOCAL_FIRST_WEIGHT = 75;

/** What the listener chose for Home: which local shelves, and how much of Home they are. */
export interface HomeSettings {
  /** The local shelf kinds the listener kept on. */
  enabled: ReadonlySet<LocalShelfKind>;
  /**
   * 0 keeps Home entirely YouTube Music's; 100 puts the local shelves first and as many of them
   * as there are. In between, local shelves take that share of the slots, evenly spread.
   */
  localWeight: number;
}

/** One slot on Home: a YouTube Music shelf, or a shelf built from the device. */
export type HomeEntry<R> = { side: 'remote'; shelf: R } | { side: 'local'; shelf: LocalShelf };

/**
 * Merges YouTube Music's shelves with the ones built from the device.
 *
 * The rules, in order of precedence:
 *  - Offline there is only the device: the remote shelves cannot be opened.
 *  - A disabled kind, and a shelf with nothing in it, are left out.
 *  - Otherwise the two sources alternate, in proportion to `localWeight`. YouTube Music's first
 *    shelf (its recently played, or the feed's lead) keeps the top of the page unless the weight
 *    is high enough that Home is mostly the listener's own.
 *  - Home is never empty because of this: with nothing local, the remote shelves go through
 *    untouched, and with nothing remote the local ones stand alone.
 */
export function compose<R>(
  remote: R[],
  local: LocalShelf[],
  settings: HomeSettings,
  online: boolean,
  remoteHasItems: (r: R) => boolean,
): Array<HomeEntry<R>> {
  const locals: Array<HomeEntry<R>> = local
    .filter((l) => settings.enabled.has(l.kind) && l.items.length > 0)
    .map((shelf): HomeEntry<R> => ({ side: 'local', shelf }));
  const remotes: Array<HomeEntry<R>> = online
    ? remote.filter(remoteHasItems).map((shelf): HomeEntry<R> => ({ side: 'remote', shelf }))
    : [];
  if (locals.length === 0) return remotes;
  if (remotes.length === 0) return locals;

  const weight = Math.min(100, Math.max(0, settings.localWeight));
  if (weight === 0) return remotes;
  if (weight === 100) return [...locals, ...remotes];

  // Lead with the remote's first shelf unless the page is mostly local.
  const out: Array<HomeEntry<R>> = [];
  const r = new Cursor(remotes);
  const l = new Cursor(locals);
  if (weight < LOCAL_FIRST_WEIGHT && r.hasNext) out.push(r.next());
  // A running balance, so the local share is spread evenly rather than bunched.
  // Mostly-local pages open on a local shelf: the balance starts already due.
  let balance = weight >= LOCAL_FIRST_WEIGHT ? 100 - weight : 0;
  while (r.hasNext || l.hasNext) {
    balance += weight;
    const takeLocal = l.hasNext && (balance >= 100 || !r.hasNext);
    if (takeLocal) {
      balance -= 100;
      out.push(l.next());
    } else if (r.hasNext) {
      out.push(r.next());
    } else {
      out.push(l.next());
    }
  }
  return out;
}

/** A forward-only walk over a list, for the two sources being merged. */
class Cursor<T> {
  private index = 0;
  private readonly items: readonly T[];

  constructor(items: readonly T[]) {
    this.items = items;
  }

  get hasNext(): boolean {
    return this.index < this.items.length;
  }

  next(): T {
    const item = this.items[this.index];
    if (item === undefined) throw new Error('Cursor read past the end');
    this.index += 1;
    return item;
  }
}
