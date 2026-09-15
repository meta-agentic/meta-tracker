import { createContext, useContext } from "react";
import type { Locale } from "../i18n/locales";
import type { ResolvedTheme, ThemePreference } from "../store/preferences";

/**
 * The one place theme and locale are published to the tree.
 * Components read from here or from `useTranslation()`; nothing reaches into
 * localStorage, `matchMedia`, or `navigator.language` on its own.
 *
 * Kept apart from `AppProviders.tsx` so that file exports only a component —
 * react-refresh cannot hot-swap a module that mixes components with values.
 */
export interface AppPreferences {
  /** What the user chose — may be `system`. */
  theme: ThemePreference;
  /** What is actually painted, after `system` is resolved against the OS. */
  resolvedTheme: ResolvedTheme;
  locale: Locale;
  setTheme: (theme: ThemePreference) => void;
  setLocale: (locale: Locale) => void;
  readonly themes: readonly ThemePreference[];
  readonly locales: readonly Locale[];
}

export const PreferencesContext = createContext<AppPreferences | null>(null);

export function useAppPreferences(): AppPreferences {
  const value = useContext(PreferencesContext);
  if (!value) {
    throw new Error("useAppPreferences must be used inside <AppProviders>");
  }
  return value;
}
