/**
 * The network a request comes from, as a string that two devices behind one router share: the IPv4 address, or an
 * IPv6 address's /64 (the part a home network hands out; the rest is per device).
 */
export function networkOf(ip: string): string {
  if (!ip.includes(':')) return ip;
  const groups = ip.split(':');
  // "::" stands for a run of zero groups; only what comes before it can be cut at four.
  const gap = groups.indexOf('');
  const head = gap === -1 ? groups : groups.slice(0, gap);
  return head
    .slice(0, 4)
    .map((g) => g.padStart(4, '0'))
    .join(':');
}
