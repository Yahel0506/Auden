/**
 * Where a dropped song goes, as "before this entry" (null: at the end). Dropping on a row below the dragged song puts it
 * after that row; dropping on a row above puts it before that row.
 */
export function dropBefore(order: ReadonlyArray<{ entryId: number }>, dragged: number, target: number): number | null {
  const from = order.findIndex((i) => i.entryId === dragged);
  const to = order.findIndex((i) => i.entryId === target);
  if (from < 0 || to < 0) return target;
  return from < to ? (order[to + 1]?.entryId ?? null) : target;
}

