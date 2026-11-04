import { useCallback, useMemo, useSyncExternalStore } from "react";
import type { Epic, ID, Issue } from "./types";

/**
 * The board filter: free text plus four multi-value facets, kept in the URL's
 * query string. Matching is plain substring comparison on lower-cased text; the
 * user's text is never compiled into a pattern.
 *
 * The URL is read defensively. Anything in it is untrusted: parameters are
 * capped in count and length, and a value the page has no use for is dropped.
 */

export const FACETS = ["epic", "label", "kind", "priority"] as const;
export type Facet = (typeof FACETS)[number];

export interface BoardFilter {
  text: string;
  epic: string[];
  label: string[];
  kind: string[];
  priority: string[];
}

export const EMPTY_FILTER: BoardFilter = { text: "", epic: [], label: [], kind: [], priority: [] };

const TEXT_PARAM = "q";
const MAX_TEXT = 200;
const MAX_VALUES = 50;
const MAX_VALUE = 100;

/** Parameters that belong to the filter, so the writer leaves every other one alone. */
const OWN_PARAMS: readonly string[] = [TEXT_PARAM, ...FACETS];

const norm = (value: string) => value.trim().toLowerCase();

export function parseFilter(search: string): BoardFilter {
  let params: URLSearchParams;
  try {
    params = new URLSearchParams(search);
  } catch {
    return EMPTY_FILTER;
  }
  const facet = (name: Facet) => {
    const seen = new Set<string>();
    const values: string[] = [];
    for (const raw of params.getAll(name)) {
      const value = raw.trim().slice(0, MAX_VALUE);
      if (value === "" || seen.has(norm(value)) || values.length >= MAX_VALUES) continue;
      seen.add(norm(value));
      values.push(value);
    }
    return values;
  };
  return {
    text: (params.get(TEXT_PARAM) ?? "").slice(0, MAX_TEXT),
    epic: facet("epic"),
    label: facet("label"),
    kind: facet("kind"),
    priority: facet("priority"),
  };
}

/** The query string for `filter` merged into `search`: foreign parameters survive. */
export function writeFilter(search: string, filter: BoardFilter): string {
  const params = new URLSearchParams(search);
  for (const name of OWN_PARAMS) params.delete(name);
  if (filter.text.trim() !== "") params.set(TEXT_PARAM, filter.text);
  for (const name of FACETS) for (const value of filter[name]) params.append(name, value);
  const out = params.toString();
  return out === "" ? "" : `?${out}`;
}

export function isFilterActive(filter: BoardFilter): boolean {
  return filter.text.trim() !== "" || FACETS.some((name) => filter[name].length > 0);
}

function inFacet(values: readonly string[], candidates: readonly (string | null | undefined)[]) {
  if (values.length === 0) return true;
  const wanted = new Set(values.map(norm));
  return candidates.some((c) => c != null && wanted.has(norm(c)));
}

/** Values of one facet combine with OR, facets and each text term with AND. */
export function matchesFilter(issue: Issue, epic: Epic | null, filter: BoardFilter): boolean {
  if (
    !inFacet(filter.epic, [epic?.key]) ||
    !inFacet(filter.label, issue.labels) ||
    !inFacet(filter.kind, [issue.type]) ||
    !inFacet(filter.priority, [issue.priority])
  ) {
    return false;
  }
  const terms = norm(filter.text).split(/\s+/).filter(Boolean);
  if (terms.length === 0) return true;
  const haystack = [issue.key, issue.title, epic?.key, epic?.title, ...issue.labels]
    .filter((part): part is string => !!part)
    .join("\n")
    .toLowerCase();
  return terms.every((term) => haystack.includes(term));
}

export function filterIssues(
  issues: readonly Issue[],
  epicsById: Readonly<Record<ID, Epic>>,
  filter: BoardFilter,
): Issue[] {
  if (!isFilterActive(filter)) return [...issues];
  return issues.filter((issue) =>
    matchesFilter(issue, issue.epicId ? (epicsById[issue.epicId] ?? null) : null, filter),
  );
}

// The URL as a store: the search string is the snapshot, so a tab switch (which
// never touches the URL), a reload and the back button all read the same value.
const CHANGE_EVENT = "vec:filterchange";

function subscribe(onChange: () => void) {
  window.addEventListener("popstate", onChange);
  window.addEventListener(CHANGE_EVENT, onChange);
  return () => {
    window.removeEventListener("popstate", onChange);
    window.removeEventListener(CHANGE_EVENT, onChange);
  };
}

const currentSearch = () => window.location.search;

export function useBoardFilter(): [BoardFilter, (next: BoardFilter) => void] {
  const search = useSyncExternalStore(subscribe, currentSearch, () => "");
  const filter = useMemo(() => parseFilter(search), [search]);
  const setFilter = useCallback((next: BoardFilter) => {
    const url = `${window.location.pathname}${writeFilter(window.location.search, next)}${window.location.hash}`;
    window.history.replaceState(window.history.state, "", url);
    window.dispatchEvent(new Event(CHANGE_EVENT));
  }, []);
  return [filter, setFilter];
}
