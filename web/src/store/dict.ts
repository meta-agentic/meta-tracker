import type { ID } from "./types";

/**
 * A record keyed by workspace ids, with no prototype.
 *
 * Ids are workspace data, so one can be `constructor`, `toString` or
 * `__proto__`. On a plain `{}` a lookup of a missing `constructor` finds
 * `Object`, and an assignment to `__proto__` replaces the prototype instead of
 * storing the entry. With no prototype, every id is only a key. The entries
 * given are copied in with `Object.assign`, which sets an own `__proto__`
 * entry on a prototype-less target as a plain key.
 */
export function dict<T>(entries?: Record<ID, T>): Record<ID, T> {
  return Object.assign(Object.create(null) as Record<ID, T>, entries);
}
