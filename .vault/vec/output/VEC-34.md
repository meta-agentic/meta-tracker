---
kind: story
space: vec
id: VEC-34
title: Extensible SPA Localization & Dynamic Theme Provider Framework
status: DONE
project: VEC
epic: VEC-7
storyPoints: 5
priority: P2
labels:
- Sprint-6
- VECTIS-025
- edition-community
- tier-Core-UI
dependencies:
- VEC-20
estimate:
  extension: 0.5
  intension: -0.4
  quadrant: complicated
  basis: up-front
  dated: '2026-08-30'
  note: 'Complicated, not complex. High extension: two providers, a token stylesheet, a store slice, locale
    dictionaries, and a migration sweep of every inline hex literal and English string across App/BoardView/BoardSwitcher/TimelineGrid.
    Low intension: R-UX-7 already decides the requirement, i18next+react-i18next is a settled pattern,
    and the one open choice (Tailwind vs CSS custom properties) was closed in refinement — Tailwind was
    never a dependency, so tokens it is. Web-only, no server, no auth. Anchor: VEC-20 (store+cache) 3,
    VEC-43 (full sprint slice) 8.'
sprint: VEC-S3
---

## Problem

`@vectis/web` has no theming layer and no localization layer at all. Every
colour in the SPA is a hardcoded hex literal inside an inline `style={{}}`
object — `#18181b`, `#e4e4e7`, `#71717a` in `src/App.tsx`, and the same pattern
again in `src/components/BoardView.tsx`, `src/components/BoardSwitcher.tsx` and
`src/profiling/TimelineGrid.tsx`. Every user-facing string ("Boards", "Timeline
profiler", "Select a board.", "Loading workspace…") is an English literal in
JSX. There is no CSS file, no `:root`, and no `<link>`/stylesheet in
`web/index.html`.

That is fine for a profiling harness and untenable for a product: it satisfies
neither **R-UX-7** (extensible locale + theme provider, light/dark by default)
nor the "no flash-of-unstyled-content, no hard refresh" behaviour this story
asks for. This story installs both providers and converts the existing surface
to use them, so that every component written after it inherits the behaviour
for free.

## Correction to the developer notes

The original developer notes prescribe *"Configure Tailwind CSS to utilize
class-driven selectors (`darkMode: 'class'`)"*. **Tailwind is not a dependency
of `@vectis/web` and never has been** (see `web/package.json`: react,
react-dom, zustand, `@tanstack/react-virtual`, and nothing else). Adopting
Tailwind is a separate, larger decision about the project's styling stack, and
it is not this story's job to make it.

Decision taken in refinement: theme via **CSS custom properties** declared once
on `:root` and overridden under a `[data-theme="dark"]` attribute on
`documentElement`. This is stack-neutral — if Tailwind is adopted later it
consumes the same tokens — and it is the smallest change that removes the
hardcoded hex literals. `i18next` + `react-i18next` from the notes stands; it is
the right library and nothing in the repo conflicts with it.

## Scope

In scope:

* A `ThemeProvider` + `LocaleProvider` (or one combined `AppProviders`) wrapping
  the SPA root in `src/main.tsx`.
* A design-token stylesheet: light and dark palettes as CSS custom properties.
* Migrating the existing inline hex literals in `App.tsx`, `BoardView.tsx`,
  `BoardSwitcher.tsx` and `TimelineGrid.tsx` to the tokens. A component still
  carrying a literal colour after this story is a defect against it.
* `i18next` + `react-i18next` wiring, with flat per-locale JSON dictionaries
  under `web/src/locales/<lang>/*.json`. Ship `en` plus one second locale, so
  that "adding a locale is data, not code" is actually demonstrated rather than
  asserted.
* Extracting every user-facing literal currently in the SPA into those
  dictionaries, including date/number formatting via the active locale.
* A `preferences` slice on the existing zustand store holding `theme` and
  `locale`, persisted through the store's existing cache layer (`src/store/cache.ts`)
  so a reload restores the choice.

Explicitly out of scope:

* **Server-side persistence of preferences.** Preferences live client-side only
  here; binding them to a user identity and a tenant is VEC-33, which depends on
  VEC-35 (auth) and must not be pulled forward into this story.
* A user-settings *panel*. This story ships the framework and a minimal toggle
  sufficient to exercise it; the designed settings surface is VEC-33.
* Adopting Tailwind or any CSS framework.
* Translating content that belongs to the user's data (board names, item titles).
  Only application chrome is localized.
* RTL layout support.

## Acceptance criteria

1. `src/main.tsx` renders the app inside a provider that supplies both the
   active theme and the active locale to the whole tree; no component reads a
   theme or a locale from anywhere else.
2. Toggling light ⇄ dark flips `data-theme` on `documentElement` and re-paints
   header, board columns, card surfaces, borders and the timeline grid from the
   token set, with no component re-mount and no page reload.
3. `grep -rnE "#[0-9a-fA-F]{3,8}" web/src --include=*.tsx` returns no matches
   outside the token stylesheet — the colour literals are gone, not merely
   supplemented.
4. The chosen theme is applied before first paint (token stylesheet plus a
   synchronous `data-theme` write from the persisted preference), so there is no
   flash of the wrong theme on reload.
5. Switching locale swaps every chrome string, and locale-formatted dates and
   numbers (the timeline axis labels in `TimelineGrid.tsx` included) re-render in
   the new locale, without unmounting the board or losing the active board
   selection, the scroll position, or any in-progress local edit state.
6. Adding a third locale requires adding one directory under `web/src/locales/`
   and registering it in one list — no change to any component under
   `src/components/` or `src/profiling/`.
7. Theme and locale survive a reload, restored from the store's existing cache
   layer.
8. Vitest coverage for: the provider's default resolution, the persisted-value
   round trip, a locale swap re-rendering translated text, and a dictionary
   completeness check asserting the non-`en` locale has no missing keys against
   `en`.

## Verification

From `web/`, all three must pass:

```
npm run test        # vitest
npm run typecheck
npm run lint
```

Plus the grep in criterion 3, run from the repo root.

## Requirement trace

R-UX-7 (extensible locale + theme provider; light/dark by default) from
ADR-VEC-01 §2. Roadmap M5.

## Dependencies

VEC-20 (zustand store with cache layering) — **DONE**, so this story is
unblocked today. It needs no server, no API and no auth, which is what makes it
one of the few VEC items pullable in the current bootstrap state.
