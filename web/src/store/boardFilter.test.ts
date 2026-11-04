import { describe, expect, it } from "vitest";
import {
  EMPTY_FILTER,
  filterIssues,
  isFilterActive,
  matchesFilter,
  parseFilter,
  writeFilter,
  type BoardFilter,
} from "./boardFilter";
import type { Epic, Issue } from "./types";

const epicA: Epic = { id: "ea", key: "E-1", title: "Search Revamp", color: "#000" };
const epicB: Epic = { id: "eb", key: "E-2", title: "Billing", color: "#000" };
const epics = { ea: epicA, eb: epicB };

function issue(over: Partial<Issue>): Issue {
  return {
    id: "i", key: "X-1", boardId: "b", epicId: null, columnId: "c", title: "Title", order: 0,
    startDate: null, dueDate: null, storyPoints: null, type: null, labels: [], priority: null,
    description: null, dependsOn: [], relates: [], ...over,
  };
}
const f = (over: Partial<BoardFilter>): BoardFilter => ({ ...EMPTY_FILTER, ...over });

const items = [
  issue({ id: "1", key: "X-1", title: "Fix login", epicId: "ea", type: "bug", priority: "P1", labels: ["web"] }),
  issue({ id: "2", key: "X-2", title: "Invoice export", epicId: "eb", type: "story", priority: "P2", labels: ["api", "Web"] }),
  issue({ id: "3", key: "X-3", title: "Plain (a.*) item", type: "task", priority: "P1" }),
];
const keys = (filter: BoardFilter) => filterIssues(items, epics, filter).map((i) => i.key);

describe("matchesFilter", () => {
  it("matches text case-insensitively against key, title, epic and labels", () => {
    expect(keys(f({ text: "x-2" }))).toEqual(["X-2"]);
    expect(keys(f({ text: "LOGIN" }))).toEqual(["X-1"]);
    expect(keys(f({ text: "revamp" }))).toEqual(["X-1"]);
    expect(keys(f({ text: "e-2" }))).toEqual(["X-2"]);
    expect(keys(f({ text: "API" }))).toEqual(["X-2"]);
  });

  it("requires every word of the text", () => {
    expect(keys(f({ text: "login web" }))).toEqual(["X-1"]);
    expect(keys(f({ text: "login api" }))).toEqual([]);
  });

  it("never reads text as a regular expression", () => {
    expect(keys(f({ text: "(a.*)" }))).toEqual(["X-3"]);
    expect(keys(f({ text: "a.*)" }))).toEqual(["X-3"]);
    expect(keys(f({ text: "x.*" }))).toEqual([]);
    expect(keys(f({ text: "[" }))).toEqual([]);
  });

  it("combines values of one facet with OR", () => {
    expect(keys(f({ kind: ["bug", "task"] }))).toEqual(["X-1", "X-3"]);
    expect(keys(f({ label: ["web", "api"] }))).toEqual(["X-1", "X-2"]);
  });

  it("combines facets with AND", () => {
    expect(keys(f({ priority: ["P1"], kind: ["bug", "task"], epic: ["E-1"] }))).toEqual(["X-1"]);
    expect(keys(f({ priority: ["P2"], kind: ["bug"] }))).toEqual([]);
  });

  it("matches facet values case-insensitively and excludes items lacking the field", () => {
    expect(keys(f({ epic: ["e-1"] }))).toEqual(["X-1"]);
    expect(matchesFilter(items[2], null, f({ epic: ["E-1"] }))).toBe(false);
  });

  it("passes everything through an empty filter", () => {
    expect(keys(EMPTY_FILTER)).toEqual(["X-1", "X-2", "X-3"]);
    expect(isFilterActive(f({ text: "  " }))).toBe(false);
  });
});

describe("URL form", () => {
  it("round-trips and keeps foreign parameters", () => {
    const filter = f({ text: "a b", epic: ["E-1", "E-2"], kind: ["bug"] });
    const search = writeFilter("?other=1&q=old&label=stale", filter);
    expect(new URLSearchParams(search).get("other")).toBe("1");
    expect(parseFilter(search)).toEqual(filter);
  });

  it("clears to nothing but the foreign parameters", () => {
    expect(writeFilter("?q=x&epic=E-1", EMPTY_FILTER)).toBe("");
    expect(writeFilter("?q=x&other=1", EMPTY_FILTER)).toBe("?other=1");
  });

  it("survives malformed and hostile query strings", () => {
    expect(parseFilter("?q=%E0%A4%A&epic=%&kind")).toBeDefined();
    const big = parseFilter(`?q=${"a".repeat(5000)}&` + Array.from({ length: 500 }, (_, n) => `label=v${n}`).join("&"));
    expect(big.text.length).toBe(200);
    expect(big.label.length).toBe(50);
    expect(parseFilter("?epic=&epic=%20&kind=Bug&kind=bug").kind).toEqual(["Bug"]);
    expect(parseFilter("?q=<script>alert(1)</script>").text).toBe("<script>alert(1)</script>");
    expect(parseFilter("?__proto__=x&constructor=y")).toEqual(EMPTY_FILTER);
  });
});
