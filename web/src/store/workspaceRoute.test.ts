// SPDX-License-Identifier: Apache-2.0
import { beforeEach, describe, expect, it } from "vitest";
import { act, renderHook, waitFor } from "@testing-library/react";
import {
  readWorkspaceParam,
  selectWorkspace,
  useWorkspaceParam,
  workspaceSearch,
} from "./workspaceRoute";
import { useBoardFilter } from "./boardFilter";

const VEC = { key: "VEC" };
const OPS = { key: "OPS" };

describe("selectWorkspace", () => {
  it("opens the workspace the URL names", () => {
    expect(selectWorkspace({ requested: "OPS", fallback: null, listed: [VEC, OPS] })).toEqual({
      kind: "open",
      key: "OPS",
    });
  });

  it("opens a named workspace before the list arrives, so a cached one paints at once", () => {
    expect(selectWorkspace({ requested: "OPS", fallback: null, listed: null })).toEqual({
      kind: "open",
      key: "OPS",
    });
  });

  it("opens the only workspace when nothing names one", () => {
    expect(selectWorkspace({ requested: null, fallback: null, listed: [OPS] })).toEqual({
      kind: "open",
      key: "OPS",
    });
  });

  it("shows the picker when nothing names one of several workspaces", () => {
    expect(selectWorkspace({ requested: null, fallback: null, listed: [VEC, OPS] })).toEqual({
      kind: "pick",
      missing: null,
    });
  });

  it("shows the picker, empty, on a server with no workspaces", () => {
    expect(selectWorkspace({ requested: null, fallback: null, listed: [] })).toEqual({
      kind: "pick",
      missing: null,
    });
  });

  it("waits for the list when nothing names a workspace", () => {
    expect(selectWorkspace({ requested: null, fallback: null, listed: null })).toEqual({
      kind: "wait",
    });
  });

  it("reports a key the server does not have as not found, beside the picker", () => {
    expect(selectWorkspace({ requested: "NOPE", fallback: null, listed: [VEC, OPS] })).toEqual({
      kind: "pick",
      missing: "NOPE",
    });
    // Even with one workspace there: the link asked for another.
    expect(selectWorkspace({ requested: "NOPE", fallback: null, listed: [OPS] })).toEqual({
      kind: "pick",
      missing: "NOPE",
    });
  });

  it("reports a key no workspace can have without waiting for the list", () => {
    expect(selectWorkspace({ requested: "ops", fallback: null, listed: null })).toEqual({
      kind: "pick",
      missing: "ops",
    });
  });

  it("uses the build-time default when the URL names none", () => {
    expect(selectWorkspace({ requested: null, fallback: "VEC", listed: [VEC, OPS] })).toEqual({
      kind: "open",
      key: "VEC",
    });
    expect(selectWorkspace({ requested: null, fallback: "VEC", listed: null })).toEqual({
      kind: "open",
      key: "VEC",
    });
  });

  it("lets the URL override the build-time default", () => {
    expect(selectWorkspace({ requested: "OPS", fallback: "VEC", listed: [VEC, OPS] })).toEqual({
      kind: "open",
      key: "OPS",
    });
  });

  it("reports a build-time default the server does not have as not found", () => {
    expect(selectWorkspace({ requested: null, fallback: "VEC", listed: [OPS] })).toEqual({
      kind: "pick",
      missing: "VEC",
    });
  });
});

describe("the URL form", () => {
  it("reads the workspace parameter, and nothing from an empty one", () => {
    expect(readWorkspaceParam("?ws=OPS")).toBe("OPS");
    expect(readWorkspaceParam("?ws=%20OPS%20&q=x")).toBe("OPS");
    expect(readWorkspaceParam("?ws=")).toBeNull();
    expect(readWorkspaceParam("?q=x")).toBeNull();
    expect(readWorkspaceParam("")).toBeNull();
  });

  it("caps what it takes from the URL", () => {
    expect(readWorkspaceParam(`?ws=${"A".repeat(500)}`)).toHaveLength(40);
  });

  it("names the new workspace and drops the board filter, keeping other parameters", () => {
    const next = new URLSearchParams(
      workspaceSearch("?ws=VEC&q=login&epic=VEC-1&label=web&kind=bug&priority=P1&fixture=slow", "OPS"),
    );
    expect([...next.entries()]).toEqual([
      ["ws", "OPS"],
      ["fixture", "slow"],
    ]);
    expect(workspaceSearch("", "OPS")).toBe("?ws=OPS");
  });
});

describe("useWorkspaceParam", () => {
  beforeEach(() => {
    window.history.replaceState(null, "", "/");
  });

  it("switches in a new history entry, clears the filter, and goes back to both", async () => {
    window.history.replaceState(null, "", "/?ws=VEC&epic=VEC-1");
    const view = renderHook(() => ({ route: useWorkspaceParam(), filter: useBoardFilter()[0] }));
    expect(view.result.current.route[0]).toBe("VEC");
    expect(view.result.current.filter.epic).toEqual(["VEC-1"]);
    const entries = window.history.length;

    act(() => view.result.current.route[1]("OPS"));

    expect(window.location.search).toBe("?ws=OPS");
    expect(window.history.length).toBe(entries + 1);
    expect(view.result.current.route[0]).toBe("OPS");
    expect(view.result.current.filter.epic).toEqual([]);

    act(() => window.history.back());

    await waitFor(() => expect(view.result.current.route[0]).toBe("VEC"));
    expect(window.location.search).toBe("?ws=VEC&epic=VEC-1");
    expect(view.result.current.filter.epic).toEqual(["VEC-1"]);
  });
});
