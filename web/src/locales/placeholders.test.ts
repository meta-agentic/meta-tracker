import enStrings from "./en/common.json";
import itStrings from "./it/common.json";

// Every `t("key", { a, b })` call must supply each placeholder its string uses, in every
// locale. A key mismatch is silent at runtime — i18next leaves "{{value}}" on screen — so
// it is checked here, against the source, instead of by eye.
const locales: Record<string, Record<string, string>> = { en: enStrings, it: itStrings };

const sources = import.meta.glob(["../**/*.{ts,tsx}", "!../**/*.test.{ts,tsx}"], {
  query: "?raw",
  import: "default",
  eager: true,
}) as Record<string, string>;

// t("some.key", { a: ..., b }) — the key and the top-level property names of the options.
const CALL = /\bt\(\s*"([^"]+)"\s*,\s*\{([^}]*)\}/g;
const paramsOf = (body: string) =>
  new Set([...body.matchAll(/(?:^|,)\s*([A-Za-z_$][\w$]*)\s*(?::|,|$)/g)].map((m) => m[1]));
const placeholdersOf = (s: string) => [...s.matchAll(/\{\{\s*([\w$]+)\s*\}\}/g)].map((m) => m[1]);

const calls = Object.entries(sources).flatMap(([file, text]) =>
  [...text.matchAll(CALL)].map((m) => ({ file, key: m[1], params: paramsOf(m[2]) })),
);

describe("translation placeholders", () => {
  it("finds the interpolated calls it is meant to check", () => {
    expect(calls.some((c) => c.key === "board.columnCount")).toBe(true);
  });

  for (const [locale, strings] of Object.entries(locales)) {
    it(`${locale}: every placeholder is supplied by its call`, () => {
      const missing = calls.flatMap(({ file, key, params }) =>
        placeholdersOf(strings[key] ?? "")
          .filter((p) => !params.has(p))
          .map((p) => `${file}: t("${key}") passes {${[...params].join(", ")}} but the string needs {{${p}}}`),
      );
      expect(missing).toEqual([]);
    });
  }
});
