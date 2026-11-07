/**
 * A press that becomes a different action when held. `down()` starts the clock; `up()` / `leave()` / `cancel()` stop it;
 * `consumeLong()` says (once) whether the press just ended was the held kind, so its click is not also a tap.
 */
export function createLongPress(onLong: () => void, ms: number, timers: { set: typeof setTimeout; clear: typeof clearTimeout } = { set: setTimeout, clear: clearTimeout }) {
  let timer: ReturnType<typeof setTimeout> | null = null;
  let fired = false;
  const cancel = () => {
    if (timer) timers.clear(timer);
    timer = null;
  };
  return {
    down() {
      fired = false;
      cancel();
      timer = timers.set(() => {
        fired = true;
        timer = null;
        onLong();
      }, ms);
    },
    cancel,
    consumeLong(): boolean {
      const was = fired;
      fired = false;
      return was;
    },
  };
}
