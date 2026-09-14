import { useMemo } from "react";
import { useTranslation } from "react-i18next";

/**
 * Locale-aware number and date formatting.
 *
 * The locale comes from the active i18next instance — the same provider the
 * strings come from — so a component never reads `navigator.language` or the
 * store directly (VEC-34 criterion 1), and a locale swap re-renders formatted
 * values along with translated ones (criterion 5).
 *
 * `Intl.*Format` construction is the expensive part, so each hook memoizes on
 * the locale and returns a plain function.
 */

export function useNumberFormat(options?: Intl.NumberFormatOptions) {
  const { i18n } = useTranslation();
  const locale = i18n.language;

  const formatter = useMemo(
    () => new Intl.NumberFormat(locale, options),
    // `options` is expected to be a literal at the call site; JSON is a cheap,
    // stable identity for it that avoids re-creating the formatter every render.
    // eslint-disable-next-line react-hooks/exhaustive-deps
    [locale, JSON.stringify(options ?? {})],
  );

  return useMemo(() => (value: number) => formatter.format(value), [formatter]);
}

export function useDateFormat(options: Intl.DateTimeFormatOptions) {
  const { i18n } = useTranslation();
  const locale = i18n.language;

  const formatter = useMemo(
    () => new Intl.DateTimeFormat(locale, options),
    // eslint-disable-next-line react-hooks/exhaustive-deps
    [locale, JSON.stringify(options)],
  );

  return useMemo(
    () => (value: Date | number) => formatter.format(value),
    [formatter],
  );
}
