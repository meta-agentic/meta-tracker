import i18next, { type i18n as I18n } from "i18next";
import { initReactI18next } from "react-i18next";
import {
  DEFAULT_LOCALE,
  NAMESPACE,
  resources,
  type Locale,
} from "./locales";
import { readPersistedPreferences } from "../store/preferences";

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

/**
 * Initialised at the *persisted* locale, not at the default.
 *
 * `AppProviders` also syncs the locale in an effect, but an effect runs after
 * the first paint — so initialising at `DEFAULT_LOCALE` would show an Italian
 * user a frame of English on every reload and then swap it. This is the same
 * pre-paint read the theme already does in `index.html` and at store
 * construction; the two preferences are now resolved the same way.
 */
export const i18n = createI18n(readPersistedPreferences().locale);
