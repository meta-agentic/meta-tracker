import { afterEach, beforeEach, describe, expect, it } from "vitest";
import { act, render, screen } from "@testing-library/react";
import { useTranslation } from "react-i18next";
import { AppProviders } from "./AppProviders";
import { useAppPreferences } from "./preferencesContext";
import { createI18n } from "../i18n";
import { SUPPORTED_LOCALES, resources } from "../i18n/locales";
import { workspaceStore } from "../store/workspaceStore";
import {
  LOCALE_KEY,
  readPersistedPreferences,
  resolveTheme,
  THEME_KEY,
} from "../store/preferences";

/**
 * Exercises the provider contract VEC-34 installs: default resolution, the
 * persisted round trip, an in-place locale swap, and dictionary completeness.
 */

function Probe() {
  const { t } = useTranslation();
  const { theme, resolvedTheme, locale, setTheme, setLocale } =
    useAppPreferences();
  return (
    <div>
      <span data-testid="theme">{theme}</span>
      <span data-testid="resolved">{resolvedTheme}</span>
      <span data-testid="locale">{locale}</span>
      <span data-testid="boards">{t("nav.boards")}</span>
      <button onClick={() => setTheme("dark")}>dark</button>
      <button onClick={() => setLocale("it")}>it</button>
    </div>
  );
}

function renderProbe() {
  // A fresh instance per test so a locale change in one does not leak into the
  // next through the module-level singleton.
  return render(
    <AppProviders i18n={createI18n(workspaceStore.getState().locale)}>
      <Probe />
    </AppProviders>,
  );
}

beforeEach(() => {
  localStorage.clear();
  act(() => {
    workspaceStore.getState().setTheme("system");
    workspaceStore.getState().setLocale("en");
  });
});

afterEach(() => {
  document.documentElement.removeAttribute("data-theme");
});

describe("theme resolution", () => {
  it("defaults to the system preference and paints a concrete theme", () => {
    renderProbe();

    expect(screen.getByTestId("theme")).toHaveTextContent("system");
    // jsdom's matchMedia stub reports no dark preference, so `system` lands on
    // light — the point is that `system` always resolves to something paintable.
    expect(screen.getByTestId("resolved")).toHaveTextContent("light");
    expect(document.documentElement.dataset.theme).toBe("light");
  });

  it("resolves `system` against the OS and lets an explicit choice win", () => {
    expect(resolveTheme("system", true)).toBe("dark");
    expect(resolveTheme("system", false)).toBe("light");
    expect(resolveTheme("light", true)).toBe("light");
    expect(resolveTheme("dark", false)).toBe("dark");
  });

  it("flips data-theme on the document without re-mounting the tree", () => {
    renderProbe();
    const probe = screen.getByTestId("boards");

    act(() => {
      screen.getByText("dark").click();
    });

    expect(document.documentElement.dataset.theme).toBe("dark");
    expect(screen.getByTestId("resolved")).toHaveTextContent("dark");
    // Same DOM node before and after: the re-paint came from the token swap,
    // not from React tearing the tree down and rebuilding it.
    expect(screen.getByTestId("boards")).toBe(probe);
  });
});

describe("persistence round trip", () => {
  it("writes both preferences through the store's synchronous cache tier", () => {
    renderProbe();

    act(() => {
      screen.getByText("dark").click();
      screen.getByText("it").click();
    });

    expect(JSON.parse(localStorage.getItem(THEME_KEY)!)).toBe("dark");
    expect(JSON.parse(localStorage.getItem(LOCALE_KEY)!)).toBe("it");
    expect(readPersistedPreferences()).toEqual({ theme: "dark", locale: "it" });
  });

  it("falls back to the defaults when the persisted values are unusable", () => {
    localStorage.setItem(THEME_KEY, JSON.stringify("chartreuse"));
    localStorage.setItem(LOCALE_KEY, JSON.stringify("xx-not-shipped"));

    expect(readPersistedPreferences()).toEqual({
      theme: "system",
      locale: "en",
    });
  });
});

describe("locale swap", () => {
  it("re-renders translated text in place", () => {
    renderProbe();
    expect(screen.getByTestId("boards")).toHaveTextContent("Boards");
    const probe = screen.getByTestId("boards");

    act(() => {
      screen.getByText("it").click();
    });

    expect(screen.getByTestId("locale")).toHaveTextContent("it");
    expect(screen.getByTestId("boards")).toHaveTextContent("Bacheche");
    // The same element carries the new text — nothing was unmounted, so a board's
    // scroll position and in-progress edit state survive a language change.
    expect(screen.getByTestId("boards")).toBe(probe);
    expect(document.documentElement.lang).toBe("it");
  });
});

describe("dictionary completeness", () => {
  it("ships at least one locale besides en", () => {
    // "Adding a locale is data, not code" is only demonstrated if a second
    // locale actually exists to demonstrate it with.
    expect(SUPPORTED_LOCALES.length).toBeGreaterThan(1);
    expect(SUPPORTED_LOCALES).toContain("en");
  });

  it.each(SUPPORTED_LOCALES.filter((l) => l !== "en"))(
    "%s has every key en has, and no extras",
    (locale) => {
      const en = Object.keys(resources.en.common).sort();
      const other = Object.keys(resources[locale].common).sort();

      expect(other.filter((k) => !en.includes(k))).toEqual([]);
      expect(en.filter((k) => !other.includes(k))).toEqual([]);
    },
  );

  it.each(SUPPORTED_LOCALES)("%s has no empty translations", (locale) => {
    const empty = Object.entries(resources[locale].common)
      .filter(([, value]) => value.trim() === "")
      .map(([key]) => key);
    expect(empty).toEqual([]);
  });
});
