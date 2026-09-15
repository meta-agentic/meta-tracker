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
      <label style={labelStyle}>
        {t("prefs.theme.label")}
        <select
          value={theme}
          onChange={(event) =>
            setTheme(event.target.value as ThemePreference)
          }
          style={selectStyle}
        >
          {themes.map((option) => (
            <option key={option} value={option}>
              {t(`prefs.theme.${option}`)}
            </option>
          ))}
        </select>
      </label>

      <label style={labelStyle}>
        {t("prefs.locale.label")}
        <select
          value={locale}
          onChange={(event) => setLocale(event.target.value)}
          style={selectStyle}
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

const labelStyle: React.CSSProperties = {
  display: "inline-flex",
  alignItems: "center",
  gap: 6,
  fontSize: 12,
  color: "var(--vec-text-muted)",
};

const selectStyle: React.CSSProperties = {
  padding: "3px 6px",
  borderRadius: 6,
  border: "1px solid var(--vec-border-strong)",
  background: "var(--vec-surface)",
  color: "var(--vec-text)",
  fontSize: 12,
};
