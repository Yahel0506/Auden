/**
 * The analyzer's waiting list (Android AnalysisQueue, v1.12): who is wanted now decides what is analysed next, not who
 * asked first. A listener skipping through a queue asks for several songs in a row and each takes the better part of a
 * minute; in arrival order the song being listened to would wait behind every one already left behind.
 *
 * [retain] names the songs worth analysing right now, most urgent first (the playing one, then the one after it).
 * Anything waiting that is not named belongs to a song that was skipped past, and is dropped; what remains is taken in
 * that order. Equal ranks (none named yet) keep their order of arrival, and an urgent request still goes to the front.
 */
export class AnalysisQueue<T extends { videoId: string }> {
  private items: T[] = [];
  private wanted: string[] | null = null;

  get length(): number {
    return this.items.length;
  }

  has(videoId: string): boolean {
    return this.items.some((i) => i.videoId === videoId);
  }

  /** Adds [item], or just moves it to the front when it is [urgent] and already waits. */
  add(item: T, urgent: boolean): void {
    const at = this.items.findIndex((i) => i.videoId === item.videoId);
    if (at >= 0) {
      if (urgent && at > 0) this.items.unshift(...this.items.splice(at, 1));
      return;
    }
    if (urgent) this.items.unshift(item);
    else this.items.push(item);
  }

  remove(videoId: string): void {
    this.items = this.items.filter((i) => i.videoId !== videoId);
  }

  /** Names who is wanted now; returns what was waiting for anyone else, which is gone from the queue. */
  retain(videoIds: readonly string[]): T[] {
    this.wanted = [...videoIds];
    const dropped = this.items.filter((i) => !this.wanted!.includes(i.videoId));
    this.items = this.items.filter((i) => this.wanted!.includes(i.videoId));
    return dropped;
  }

  /** Whether [videoId] is still worth the work: always, until someone has said who is. */
  isWanted(videoId: string): boolean {
    return this.wanted === null || this.wanted.includes(videoId);
  }

  /** The most urgent waiting item, taken out: the lowest rank, ties in the order they wait. */
  take(): T | undefined {
    if (!this.items.length) return undefined;
    // A song nobody named (asked for after the last retain) goes after the named ones, never ahead of the playing song.
    const rank = (i: T) => {
      if (!this.wanted) return 0;
      const at = this.wanted.indexOf(i.videoId);
      return at < 0 ? this.wanted.length : at;
    };
    let best = 0;
    for (let k = 1; k < this.items.length; k++) if (rank(this.items[k]!) < rank(this.items[best]!)) best = k;
    return this.items.splice(best, 1)[0];
  }
}
