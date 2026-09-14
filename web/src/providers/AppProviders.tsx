import {
  useCallback,
  useEffect,
  useLayoutEffect,
  useMemo,
  useSyncExternalStore,
  type ReactNode,
} from "react";
import { I18nextProvider } from "react-i18next";
import type { i18n as I18n } from "i18next";
import { i18n as defaultI18n } from "../i18n";
import { SUPPORTED_LOCALES } from "../i18n/locales";
import { useWorkspaceStore } from "../store/workspaceStore";
import { resolveTheme, THEME_PREFERENCES } from "../store/preferences";
import {
  PreferencesContext,
  type AppPreferences,
} from "./preferencesContext";

const DARK_QUERY = "(prefers-color-scheme: dark)";

/**
 * Subscribes to the OS colour-scheme preference. Without this, choosing
 * `system` would freeze whatever the OS happened to be at load time.
 */
function usePrefersDark(): boolean {
  const subscribe = useCallback((onChange: () => void) => {
    if (typeof matchMedia !== "function") return () => {};
    const query = matchMedia(DARK_QUERY);
    query.addEventListener("change", onChange);
    return () => query.removeEventListener("change", onChange);
  }, []);

  return useSyncExternalStore(
    subscribe,
    () => typeof matchMedia === "function" && matchMedia(DARK_QUERY).matches,
    // Server/prerender has no OS preference to read; light is the documented default.
    () => false,
  );
}

export interface AppProvidersProps {
  children: ReactNode;
  /** Overridable so a test can supply an isolated i18next instance. */
  i18n?: I18n;
}

/**
 * Supplies the active theme and the active locale to the whole tree. Every
 * component below reads chrome text through `useTranslation()` and colour
 * through `var(--vec-*)`; none of them resolves a preference for itself.
 */
export function AppProviders({ children, i18n = defaultI18n }: AppProvidersProps) {
  const theme = useWorkspaceStore((s) => s.theme);
  const locale = useWorkspaceStore((s) => s.locale);
  const setTheme = useWorkspaceStore((s) => s.setTheme);
  const setLocale = useWorkspaceStore((s) => s.setLocale);
  const prefersDark = usePrefersDark();

  const resolvedTheme = resolveTheme(theme, prefersDark);

  // Layout effect, not effect: the attribute has to be on <html> before the
  // browser paints the committed tree, or toggling flashes the old palette for
  // a frame. The pre-paint bootstrap in index.html covers the very first load;
  // this covers every change after it.
  useLayoutEffect(() => {
    const root = document.documentElement;
    root.dataset.theme = resolvedTheme;
    root.style.colorScheme = resolvedTheme;
  }, [resolvedTheme]);

  useEffect(() => {
    document.documentElement.lang = locale;
  }, [locale]);

  // changeLanguage mutates the shared instance and notifies its subscribers, so
  // every `useTranslation()` consumer re-renders in place. No key, no re-mount —
  // the board keeps its scroll position and the active selection (criterion 5).
  useEffect(() => {
    if (i18n.language !== locale) void i18n.changeLanguage(locale);
  }, [i18n, locale]);

  const value = useMemo<AppPreferences>(
    () => ({
      theme,
      resolvedTheme,
      locale,
      setTheme,
      setLocale,
      themes: THEME_PREFERENCES,
      locales: SUPPORTED_LOCALES,
    }),
    [theme, resolvedTheme, locale, setTheme, setLocale],
  );

  return (
    <I18nextProvider i18n={i18n}>
      <PreferencesContext.Provider value={value}>
        {children}
      </PreferencesContext.Provider>
    </I18nextProvider>
  );
}
