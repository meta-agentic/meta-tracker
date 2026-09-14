import { uiTracking } from "./cache";
import { DEFAULT_LOCALE, isSupportedLocale, type Locale } from "../i18n/locales";

/**
 * User preferences slice: theme and locale.
 *
 * These ride on the store's *synchronous* tier (`uiTracking` → localStorage)
 * rather than the async IndexedDB tier that holds board data. That is not a
 * convenience: the theme has to be known before the first paint, and an async
 * read cannot be. The same reasoning already put `activeBoardId` there.
 *
 * Keys are exported because the pre-paint bootstrap in `index.html` reads the
 * same localStorage entries before any module has loaded; if one moves, both
 * move.
 */

export const THEME_KEY = "ui:theme";
export const LOCALE_KEY = "ui:locale";

/**
 * `system` is a real third state, not the absence of a choice — it means
 * "follow prefers-color-scheme", and it is the default.
 */
export type ThemePreference = "light" | "dark" | "system";

/** The theme actually painted, once `system` has been resolved against the OS. */
export type ResolvedTheme = "light" | "dark";

export const THEME_PREFERENCES: readonly ThemePreference[] = [
  "system",
  "light",
  "dark",
];

function isThemePreference(value: unknown): value is ThemePreference {
  return (
    value === "light" || value === "dark" || value === "system"
  );
}

export interface PreferencesState {
  theme: ThemePreference;
  locale: Locale;
  setTheme: (theme: ThemePreference) => void;
  setLocale: (locale: Locale) => void;
}

/** Resolves `system` against the OS; both explicit values pass through. */
export function resolveTheme(
  preference: ThemePreference,
  prefersDark: boolean = typeof matchMedia === "function" &&
    matchMedia("(prefers-color-scheme: dark)").matches,
): ResolvedTheme {
  if (preference === "system") return prefersDark ? "dark" : "light";
  return preference;
}

/**
 * Reads the persisted preferences synchronously. Called at store construction
 * rather than inside `hydrate()`, so the values are correct on the very first
 * render and the theme never has to be corrected after paint.
 */
export function readPersistedPreferences(): {
  theme: ThemePreference;
  locale: Locale;
} {
  const theme = uiTracking.read<unknown>(THEME_KEY, "system");
  const locale = uiTracking.read<unknown>(LOCALE_KEY, DEFAULT_LOCALE);
  return {
    theme: isThemePreference(theme) ? theme : "system",
    // A locale persisted by an older build (or hand-edited) may no longer be
    // shipped; falling back beats rendering a screen of missing keys.
    locale: isSupportedLocale(locale) ? locale : DEFAULT_LOCALE,
  };
}

/**
 * Builds the preferences slice. `set` is the host store's setter, so the
 * preferences live on the same store instance every component already reads —
 * there is no second store and no second subscription.
 */
export function createPreferencesSlice(
  set: (partial: Partial<PreferencesState>) => void,
): PreferencesState {
  const persisted = readPersistedPreferences();

  return {
    theme: persisted.theme,
    locale: persisted.locale,

    setTheme: (theme) => {
      set({ theme });
      uiTracking.write(THEME_KEY, theme);
    },

    setLocale: (locale) => {
      set({ locale });
      uiTracking.write(LOCALE_KEY, locale);
    },
  };
}
