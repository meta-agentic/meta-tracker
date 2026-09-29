import { useTranslation } from "react-i18next";
import { useAppPreferences } from "../providers/preferencesContext";
import { localeLabel } from "../i18n/locales";
import type { ThemePreference } from "../store/preferences";

/**
 * The minimal surface that exercises the two providers.
 *
 * Deliberately not a settings panel: this ships the framework, and the
 * designed preferences surface (with server-side persistence bound to a user
 * identity) is a later story. Two selects are enough to prove a theme flip re-paints
 * without a re-mount and a locale swap re-renders in place.
 */
export function PreferenceControls() {
  const { t } = useTranslation();
  const { theme, setTheme, locale, setLocale, themes, locales } =
    useAppPreferences();

  return (
    <>
      <label className="vec-pref">
        <span className="vec-pref__label">{t("prefs.theme.label")}</span>
        <select
          value={theme}
          onChange={(event) =>
            setTheme(event.target.value as ThemePreference)
          }
          className="vec-select"
        >
          {themes.map((option) => (
            <option key={option} value={option}>
              {t(`prefs.theme.${option}`)}
            </option>
          ))}
        </select>
      </label>

      <label className="vec-pref">
        <span className="vec-pref__label">{t("prefs.locale.label")}</span>
        <select
          value={locale}
          onChange={(event) => setLocale(event.target.value)}
          className="vec-select"
        >
          {locales.map((option) => (
            <option key={option} value={option}>
              {localeLabel(option)}
            </option>
          ))}
        </select>
      </label>
    </>
  );
}
