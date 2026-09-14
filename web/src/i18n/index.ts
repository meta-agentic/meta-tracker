import i18next, { type i18n as I18n } from "i18next";
import { initReactI18next } from "react-i18next";
import {
  DEFAULT_LOCALE,
  NAMESPACE,
  resources,
  type Locale,
} from "./locales";

/**
 * The shared i18next instance.
 *
 * Created once at module load and handed to `AppProviders`, so every consumer
 * resolves through the same instance and a `changeLanguage` call re-renders the
 * whole tree in place rather than re-mounting it.
 */
export function createI18n(locale: Locale = DEFAULT_LOCALE): I18n {
  const instance = i18next.createInstance();

  void instance.use(initReactI18next).init({
    resources,
    lng: locale,
    fallbackLng: DEFAULT_LOCALE,
    defaultNS: NAMESPACE,
    ns: [NAMESPACE],
    // Values are already escaped by React on render; letting i18next escape them
    // as well turns an apostrophe into `&#39;` in the DOM.
    interpolation: { escapeValue: false },
    // The dictionaries are flat: a key is a key, not a path into a nested
    // object, so "board.empty" must not be read as board → empty.
    keySeparator: false,
    nsSeparator: false,
  });

  return instance;
}

export const i18n = createI18n();
