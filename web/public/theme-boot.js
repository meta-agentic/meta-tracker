// Pre-paint theme bootstrap. `index.html` loads it as a classic, blocking
// script in <head>, so it runs before the module bundle is fetched and a reload
// into dark mode never flashes the light palette.
//
// It is a file rather than an inline <script> because the server's
// Content-Security-Policy allows scripts from 'self' only (VEC-78).
//
// The keys and the value shape are owned by src/store/preferences.ts
// (THEME_KEY / LOCALE_KEY, JSON-encoded) — they are repeated here because
// this must execute with no module graph available. If they change there,
// change them here.
(function () {
  try {
    var raw = localStorage.getItem("ui:theme");
    var pref = raw ? JSON.parse(raw) : "system";
    var dark =
      pref === "dark" ||
      (pref !== "light" && matchMedia("(prefers-color-scheme: dark)").matches);
    var theme = dark ? "dark" : "light";
    document.documentElement.dataset.theme = theme;
    document.documentElement.style.colorScheme = theme;

    var locale = localStorage.getItem("ui:locale");
    if (locale) document.documentElement.lang = JSON.parse(locale);
  } catch (e) {
    // Private mode, blocked storage, or corrupt JSON — the stylesheet's
    // :root defaults still give a correct light/system render.
  }
})();
