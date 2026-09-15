/**
 * The locale registry.
 *
 * Adding a locale is *data, not code*: drop `web/src/locales/<lang>/common.json`
 * in place and it is picked up here. The glob below is the "one list" of
 * There is no per-locale branch anywhere else in
 * the SPA, and no component under `src/components/` or `src/profiling/` has to
 * change.
 *
 * `eager: true` bundles every dictionary into the main chunk. With two small
 * flat dictionaries that is cheaper than the request a lazy load would cost, and
 * it keeps a locale switch synchronous — criterion 5 requires the swap to happen
 * without unmounting the board, which a suspending async load would not give us.
 */

const modules = import.meta.glob<{ default: Record<string, string> }>(
  "../locales/*/common.json",
  { eager: true },
);

/** `../locales/en/common.json` → `en` */
function localeFromPath(path: string): string {
  return path.split("/").at(-2)!;
}

export const NAMESPACE = "common" as const;

export type Dictionary = Record<string, string>;

export const resources: Record<string, Record<typeof NAMESPACE, Dictionary>> =
  Object.fromEntries(
    Object.entries(modules).map(([path, module]) => [
      localeFromPath(path),
      { [NAMESPACE]: module.default },
    ]),
  );

/** Sorted so the locale picker's order does not depend on glob iteration order. */
export const SUPPORTED_LOCALES = Object.keys(resources).sort();

export type Locale = string;

export const DEFAULT_LOCALE: Locale = "en";

export function isSupportedLocale(value: unknown): value is Locale {
  return typeof value === "string" && SUPPORTED_LOCALES.includes(value);
}

/**
 * The locale's own name for itself ("English", "italiano"), from the platform
 * rather than from a hand-maintained table — another thing a new locale does not
 * have to supply.
 */
export function localeLabel(locale: Locale): string {
  try {
    return new Intl.DisplayNames([locale], { type: "language" }).of(locale) ?? locale;
  } catch {
    return locale;
  }
}
