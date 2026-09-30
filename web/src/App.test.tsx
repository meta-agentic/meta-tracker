import { beforeEach, describe, expect, it, vi } from "vitest";
import { act, fireEvent, render, screen, waitFor, within } from "@testing-library/react";
import { App } from "./App";
import { AppProviders } from "./providers/AppProviders";
import { IndexedDbCache } from "./store/cache";
import { createWorkspaceStore, workspaceStore } from "./store/workspaceStore";
import { createWorkspaceApiClient } from "./api/client";
import { readApiConfig } from "./api/config";
import { toWorkspaceSnapshot } from "./api/adapter";
import {
  boardFixture,
  createGate,
  epicItemFixture,
  createRecordedTransport,
  itemFixture,
  snapshotRecording,
  workspaceFixture,
  type Recording,
} from "./test/recordedTransport";
import { giveElementSize } from "./test/layout";

/**
 * The application's side of VEC-42: the four states the synthetic path never
 * needed, rendered. The transport is recorded, so nothing here needs a server.
 */

const config = readApiConfig({ VITE_VECTIS_WORKSPACE_KEY: "VEC" });

/**
 * The components render from the module-level `workspaceStore`, so these tests
 * drive that store rather than an injected one — only the transport is
 * substituted. The cache is reached through a throwaway store over the same
 * IndexedDB, which is the store's own public way to seed and clear it.
 */
function mount(recording: Recording) {
  const recorded = createRecordedTransport(recording);
  const view = render(
    <AppProviders>
      <App
        sync={{
          createClient: () =>
            createWorkspaceApiClient({ config, transport: recorded.transport }),
        }}
      />
    </AppProviders>,
  );
  return { recorded, view };
}

async function clearPersistedWorkspace() {
  createWorkspaceStore(new IndexedDbCache()).getState().reset();
  await new Promise((resolve) => setTimeout(resolve, 50));
}

async function warmCache() {
  const seed = createWorkspaceStore(new IndexedDbCache());
  seed.getState().ingestSnapshot(
    toWorkspaceSnapshot({
      workspace: workspaceFixture,
      boards: [boardFixture],
      items: [itemFixture()],
    }),
  );
  seed.getState().setActiveBoard(boardFixture.id);
  await new Promise((resolve) => setTimeout(resolve, 300));
}

beforeEach(async () => {
  localStorage.clear();
  workspaceStore.getState().reset();
  // The open tab and the expanded rows persist across reloads, so they would
  // also leak across tests.
  workspaceStore.getState().setActiveTab("boards");
  workspaceStore.getState().setTreeExpanded([]);
  await clearPersistedWorkspace();
});

describe("App", () => {
  // AC1, AC4
  it("shows a loading state on a cold load, then the served board", async () => {
    const gate = createGate();
    mount({ ...snapshotRecording(), gate: gate.promise });

    expect(screen.getByText("Loading workspace…")).toBeInTheDocument();
    // No board columns while loading: an empty board must not be mistaken for a
    // loaded one that happens to be empty.
    expect(screen.queryByText("To Do")).not.toBeInTheDocument();

    gate.release();

    await waitFor(() => expect(screen.getByText("To Do")).toBeInTheDocument());
    expect(screen.getByText("In Progress")).toBeInTheDocument();
    expect(screen.getByText("Wire the client")).toBeInTheDocument();
    expect(screen.getByRole("button", { name: "Delivery" })).toBeInTheDocument();
  });

  it("draws the loading skeleton with as many columns as the board last shown", async () => {
    const skeletonColumns = (container: HTMLElement) =>
      container.querySelectorAll(".vec-column--skeleton").length;

    // Nothing shown yet: the default template's five.
    const first = createGate();
    const { view } = mount({ ...snapshotRecording(), gate: first.promise });
    expect(skeletonColumns(view.container)).toBe(5);
    first.release();
    await screen.findByText("Wire the client");
    view.unmount();

    // The fixture board has two columns; the next cold load draws two.
    await clearPersistedWorkspace();
    workspaceStore.getState().reset();
    const second = createGate();
    const again = mount({ ...snapshotRecording(), gate: second.promise });
    expect(skeletonColumns(again.view.container)).toBe(2);
    second.release();
    await screen.findByText("Wire the client");
  });

  // AC5
  it("revalidates a cached board in place, without remounting it", async () => {
    await warmCache();

    const gate = createGate();
    mount({
      ...snapshotRecording([
        itemFixture(),
        itemFixture({ id: "item-2", key: "VEC-3", rank: "n", title: "Second card" }),
      ]),
      gate: gate.promise,
    });

    // Cached data paints before the server answers.
    await waitFor(() => expect(screen.getByText("Wire the client")).toBeInTheDocument());
    const sectionBeforeRefresh = screen.getByText("To Do").closest("section");
    expect(sectionBeforeRefresh).not.toBeNull();
    expect(screen.queryByText("Second card")).not.toBeInTheDocument();

    gate.release();

    await waitFor(() => expect(screen.getByText("Second card")).toBeInTheDocument());
    // Same DOM node: React reconciled in place. A remount would have replaced
    // it and reset the scroll position with it.
    expect(screen.getByText("To Do").closest("section")).toBe(sectionBeforeRefresh);
  });

  // AC6
  it("keeps a cached board on screen when revalidation fails, and says it is stale", async () => {
    await warmCache();

    mount({ routes: {}, networkError: new TypeError("Failed to fetch") });

    // The loading placeholder is a status region too, so wait for this one's text
    // rather than for any status to exist.
    await waitFor(() =>
      expect(screen.getByRole("status")).toHaveTextContent(
        "Showing cached data — the last refresh failed.",
      ),
    );
    // The board is still rendered. The notice sits beside it, not over it.
    expect(screen.getByText("To Do")).toBeInTheDocument();
    expect(screen.getByText("Wire the client")).toBeInTheDocument();
    expect(screen.queryByRole("alert")).not.toBeInTheDocument();
  });

  // AC7
  it("shows an error with a working retry when there is no cache to fall back on", async () => {
    const { recorded } = mount({
      routes: {},
      networkError: new TypeError("Failed to fetch"),
    });

    const alert = await screen.findByRole("alert");
    expect(alert).toHaveTextContent("The workspace could not be loaded.");
    expect(screen.queryByText("To Do")).not.toBeInTheDocument();

    recorded.play(snapshotRecording());
    fireEvent.click(screen.getByRole("button", { name: "Try again" }));

    await waitFor(() => expect(screen.getByText("To Do")).toBeInTheDocument());
    expect(screen.queryByRole("alert")).not.toBeInTheDocument();
  });

  it("offers no retry for a failure repeating cannot fix", async () => {
    mount({
      routes: { "/workspaces/VEC": { body: { id: 1, key: "VEC", name: "Vectis" } } },
    });

    await screen.findByRole("alert");
    expect(screen.queryByRole("button", { name: "Try again" })).not.toBeInTheDocument();
  });

  // AC3
  it("renders the timeline profiler with no server and no workspace data", async () => {
    mount({ routes: {}, networkError: new TypeError("Failed to fetch") });

    await screen.findByRole("alert");
    fireEvent.click(screen.getByRole("button", { name: "Timeline profiler" }));

    expect(screen.getByRole("button", { name: "Run dual-axis profile" })).toBeInTheDocument();
    expect(screen.queryByRole("alert")).not.toBeInTheDocument();
  });
});

describe("board cards and the item detail panel", () => {
  const richItems = [
    epicItemFixture,
    itemFixture({
      fields: {
        type: "story",
        parentId: "epic-1",
        storyPoints: 3,
        labels: ["web", "design"],
        description: "Render the board from the store.",
        dependencies: ["VEC-3", "VEC-404"],
      },
    }),
    itemFixture({
      id: "item-2",
      key: "VEC-3",
      rank: "n",
      title: "Second card",
      fields: { type: "bug" },
    }),
  ];

  it("names the workspace in the shell", async () => {
    mount(snapshotRecording(richItems));

    await screen.findByText("Wire the client");
    const banner = screen.getByRole("banner");
    expect(within(banner).getByText("Vectis", { selector: ".vec-workspace__name" })).toBeInTheDocument();
    expect(within(banner).getByText("VEC")).toBeInTheDocument();
  });

  it("shows key, title, type, points, epic and labels on a card", async () => {
    mount(snapshotRecording(richItems));

    const card = (await screen.findByText("Wire the client")).closest("button")!;
    expect(card).toHaveTextContent("VEC-2");
    expect(card).toHaveTextContent("Story");
    expect(card).toHaveTextContent("Client shell");
    expect(card).toHaveTextContent("web");
    expect(card).toHaveTextContent("design");
    expect(within(card).getByText("3 story points")).toBeInTheDocument();
  });

  it("marks an empty column instead of leaving a blank gap", async () => {
    mount(snapshotRecording(richItems));

    const doing = (await screen.findByRole("heading", { name: "In Progress" })).closest("section")!;
    expect(within(doing).getByText("No items")).toBeInTheDocument();
  });

  it("opens the detail panel from a card, closes it on Escape and returns focus", async () => {
    mount(snapshotRecording(richItems));

    const card = (await screen.findByText("Wire the client")).closest("button")!;
    card.focus();
    fireEvent.click(card);

    const dialog = screen.getByRole("dialog", { name: "Wire the client" });
    expect(dialog).toHaveTextContent("Render the board from the store.");
    expect(dialog).toHaveTextContent("To Do");
    // Focus moves into the panel, onto its close button.
    expect(within(dialog).getByRole("button", { name: "Close" })).toHaveFocus();

    fireEvent.keyDown(dialog, { key: "Escape" });

    await waitFor(() => expect(screen.queryByRole("dialog")).not.toBeInTheDocument());
    expect(card).toHaveFocus();
  });

  it("follows a link to a loaded item and shows an unloaded one as text", async () => {
    mount(snapshotRecording(richItems));

    fireEvent.click((await screen.findByText("Wire the client")).closest("button")!);
    const dialog = screen.getByRole("dialog");

    expect(within(dialog).getByText("VEC-404")).toBeInTheDocument();
    expect(within(dialog).queryByRole("button", { name: /VEC-404/ })).not.toBeInTheDocument();

    fireEvent.click(within(dialog).getByRole("button", { name: /VEC-3/ }));
    expect(screen.getByRole("dialog", { name: "Second card" })).toHaveTextContent("Bug");
  });

  it("keeps the sheet modal when focus lands outside it", async () => {
    const { view } = mount(snapshotRecording(richItems));

    fireEvent.click((await screen.findByText("Wire the client")).closest("button")!);
    const dialog = screen.getByRole("dialog");

    // The rest of the page is inert while the sheet is open…
    expect(view.container).toHaveAttribute("inert");
    // …and if focus ends up outside anyway, Tab brings it back in and Escape
    // still closes, because both are handled at the document.
    act(() => document.body.focus());
    fireEvent.keyDown(document.body, { key: "Tab" });
    expect(dialog.contains(document.activeElement)).toBe(true);

    act(() => document.body.focus());
    fireEvent.keyDown(document.body, { key: "Escape" });
    await waitFor(() => expect(screen.queryByRole("dialog")).not.toBeInTheDocument());
    expect(view.container).not.toHaveAttribute("inert");
  });

  it("keeps Shift+Tab inside the sheet when the panel itself has focus", async () => {
    mount(snapshotRecording(richItems));

    fireEvent.click((await screen.findByText("Wire the client")).closest("button")!);
    const dialog = screen.getByRole("dialog");
    // A click on the sheet's text focuses the panel, which sits before its
    // first control: Shift+Tab from there must wrap to the last one.
    act(() => dialog.focus());
    fireEvent.keyDown(dialog, { key: "Tab", shiftKey: true });

    const controls = within(dialog).getAllByRole("button");
    expect(controls[controls.length - 1]).toHaveFocus();
  });

  it("renders a board whose column ids are object prototype members", async () => {
    const protoBoard = {
      ...boardFixture,
      columns: [
        { id: "constructor", name: "Constructor", position: 0 },
        { id: "__proto__", name: "Proto", position: 1 },
        { id: "toString", name: "Empty", position: 2 },
      ],
    };
    mount({
      routes: {
        "/boards": { body: [protoBoard] },
        "/items": { body: [itemFixture({ columnId: "__proto__" })] },
        "/workspaces/VEC": { body: workspaceFixture },
      },
    });

    const proto = (await screen.findByRole("heading", { name: "Proto" })).closest("section")!;
    expect(within(proto).getByText("Wire the client")).toBeInTheDocument();
    for (const name of ["Constructor", "Empty"]) {
      const column = screen.getByRole("heading", { name }).closest("section")!;
      expect(within(column).getByText("No items")).toBeInTheDocument();
    }
    expect(screen.queryByRole("alert")).not.toBeInTheDocument();
  });

  it("keeps the shell and the board switcher when the board fails to render", async () => {
    // React logs the caught error; expected here, so keep the output clean.
    const spy = vi.spyOn(console, "error").mockImplementation(() => {});
    const swallow = (event: ErrorEvent) => event.preventDefault();
    window.addEventListener("error", swallow);
    mount(snapshotRecording(richItems));
    await screen.findByText("Wire the client");

    // A value no decoder anticipated, reaching the board through the store.
    act(() => {
      const { issuesById } = workspaceStore.getState();
      const broken = { ...issuesById["item-1"], labels: undefined as unknown as string[] };
      workspaceStore.setState({ issuesById: { ...issuesById, "item-1": broken } });
    });

    const alert = await screen.findByRole("alert");
    expect(alert).toHaveTextContent("This board could not be shown");
    expect(alert).not.toHaveTextContent(/reload/i);
    expect(screen.getByRole("banner")).toBeInTheDocument();
    expect(screen.getByRole("navigation", { name: "Boards in this workspace" })).toBeInTheDocument();
    expect(screen.getByRole("button", { name: "Delivery" })).toBeInTheDocument();
    window.removeEventListener("error", swallow);
    spy.mockRestore();
  });

  it("renders a type named after an object prototype member", async () => {
    mount(
      snapshotRecording([
        itemFixture({ fields: { type: "constructor", storyPoints: 1 } }),
        itemFixture({ id: "item-2", key: "VEC-3", rank: "n", title: "Second card", fields: { type: "__proto__" } }),
      ]),
    );

    const card = (await screen.findByText("Wire the client")).closest("button")!;
    expect(card).toHaveTextContent("constructor");
    // Singular, not "1 story points".
    expect(within(card).getByText("1 story point")).toBeInTheDocument();
    expect(screen.getByText("Second card")).toBeInTheDocument();
  });

  it("says so when the workspace has no boards", async () => {
    mount({
      routes: {
        "/boards": { body: [] },
        "/items": { body: [] },
        "/workspaces/VEC": { body: workspaceFixture },
      },
    });

    expect(await screen.findByText("No boards yet")).toBeInTheDocument();
  });
});

describe("backlog tab", () => {
  giveElementSize("backlog-scroll", { width: 800, height: 600 });

  it("shows the backlog as a tree of epics over their stories", async () => {
    mount({
      ...snapshotRecording([
        epicItemFixture,
        itemFixture({ fields: { parentId: "epic-1" } }),
        itemFixture({ id: "item-2", key: "VEC-3", rank: "n", title: "No epic yet" }),
      ]),
    });
    await screen.findByText("Wire the client");

    fireEvent.click(screen.getByRole("button", { name: "Backlog" }));

    const tree = screen.getByRole("tree", { name: "Backlog of Delivery" });
    const epicRow = within(tree).getByRole("treeitem", { name: /Client shell/ });
    expect(epicRow).toHaveAttribute("aria-expanded", "false");
    expect(within(tree).getByRole("treeitem", { name: /No epic yet/ })).toBeInTheDocument();
    expect(within(tree).queryByText("Wire the client")).not.toBeInTheDocument();

    fireEvent.click(epicRow);
    expect(epicRow).toHaveAttribute("aria-expanded", "true");
    expect(within(tree).getByText("Wire the client")).toBeInTheDocument();
    expect(workspaceStore.getState().activeTab).toBe("backlog");
  });

  it("opens an item in the detail sheet and returns focus to its row", async () => {
    mount(
      snapshotRecording([
        epicItemFixture,
        itemFixture({ fields: { parentId: "epic-1", description: "Render the board from the store." } }),
      ]),
    );
    await screen.findByText("Wire the client");
    fireEvent.click(screen.getByRole("button", { name: "Backlog" }));

    const tree = screen.getByRole("tree");
    fireEvent.click(within(tree).getByRole("treeitem", { name: /Client shell/ }));
    const storyRow = within(tree).getByRole("treeitem", { name: /Wire the client/ });
    expect(storyRow).toHaveAttribute("aria-haspopup", "dialog");

    // From the keyboard: the story is the active row, Enter opens it.
    fireEvent.keyDown(tree, { key: "ArrowDown" });
    expect(tree).toHaveAttribute("aria-activedescendant", storyRow.id);
    fireEvent.keyDown(tree, { key: "Enter" });

    const dialog = screen.getByRole("dialog", { name: "Wire the client" });
    expect(dialog).toHaveTextContent("Render the board from the store.");
    expect(within(dialog).getByRole("button", { name: "Close" })).toHaveFocus();

    fireEvent.keyDown(dialog, { key: "Escape" });
    await waitFor(() => expect(screen.queryByRole("dialog")).not.toBeInTheDocument());
    // Back on the tree, with the row that opened the sheet still the active one.
    expect(tree).toHaveFocus();
    expect(tree).toHaveAttribute("aria-activedescendant", storyRow.id);

    // A click opens it too.
    fireEvent.click(storyRow);
    expect(screen.getByRole("dialog", { name: "Wire the client" })).toBeInTheDocument();
  });

  it("shows the roots after Collapse all from a scrolled tree", async () => {
    mount(
      snapshotRecording([
        epicItemFixture,
        itemFixture({ fields: { parentId: "epic-1" } }),
        itemFixture({ id: "item-2", key: "VEC-3", rank: "n", title: "No epic yet" }),
      ]),
    );
    await screen.findByText("Wire the client");
    fireEvent.click(screen.getByRole("button", { name: "Backlog" }));
    fireEvent.click(screen.getByRole("button", { name: "Expand all" }));

    const tree = screen.getByRole("tree");
    act(() => {
      tree.scrollTop = 200;
      fireEvent.scroll(tree);
    });
    fireEvent.click(screen.getByRole("button", { name: "Collapse all" }));

    // No row survives above the viewport to hold still for, so no spacer: the
    // canvas is the roots' height, and the browser's clamp brings them into view.
    const canvas = tree.firstElementChild as HTMLElement;
    const roots = within(tree).getAllByRole("treeitem");
    expect(roots).toHaveLength(2);
    expect(parseFloat(canvas.style.height)).toBe(roots.length * 40);
  });

  describe("after a single collapse near the end has held the viewport", () => {
    // Two epics: a small one first, then one with thirty stories that runs to
    // the end of the list, so collapsing it from near the end needs the spacer.
    const bigEpic = { ...epicItemFixture, id: "epic-2", key: "VEC-5", rank: "b", title: "Big epic" };
    const stories = Array.from({ length: 30 }, (_, n) =>
      itemFixture({
        id: `story-${n}`,
        key: `VEC-${100 + n}`,
        rank: `z${String(n).padStart(3, "0")}`,
        title: `Story ${n}`,
        fields: { parentId: "epic-2" },
      }),
    );

    async function collapseBigEpicNearTheEnd() {
      mount(
        snapshotRecording([
          epicItemFixture,
          itemFixture({ fields: { parentId: "epic-1" } }),
          bigEpic,
          ...stories,
        ]),
      );
      await screen.findByText("Wire the client");
      fireEvent.click(screen.getByRole("button", { name: "Backlog" }));
      fireEvent.click(screen.getByRole("button", { name: "Expand all" }));

      const tree = screen.getByRole("tree");
      // Make the big epic the active row, then scroll near the end and
      // collapse it from the keyboard.
      fireEvent.keyDown(tree, { key: "Home" });
      fireEvent.keyDown(tree, { key: "ArrowDown" });
      fireEvent.keyDown(tree, { key: "ArrowDown" });
      expect(tree).toHaveAttribute("aria-activedescendant", "backlog-row-epic-2");
      act(() => {
        tree.scrollTop = 700;
        fireEvent.scroll(tree);
      });
      fireEvent.keyDown(tree, { key: "ArrowLeft" });
      const canvas = tree.firstElementChild as HTMLElement;
      // The spacer holds the viewport's bottom for that one collapse.
      expect(parseFloat(canvas.style.height)).toBe(700 + 600);
      return { tree, canvas };
    }

    // Every root on screen: no spacer, and the rows fit the 600px viewport,
    // so the browser's clamp brings the tree back to the top.
    function expectRootsInView(tree: HTMLElement, canvas: HTMLElement) {
      const roots = within(tree).getAllByRole("treeitem");
      expect(roots).toHaveLength(2);
      expect(parseFloat(canvas.style.height)).toBe(roots.length * 40);
    }

    it("does not carry the spacer into Collapse all", async () => {
      const { tree, canvas } = await collapseBigEpicNearTheEnd();
      fireEvent.click(screen.getByRole("button", { name: "Collapse all" }));
      expectRootsInView(tree, canvas);
    });

    it("does not carry the spacer through Expand all into Collapse all", async () => {
      const { tree, canvas } = await collapseBigEpicNearTheEnd();
      fireEvent.click(screen.getByRole("button", { name: "Expand all" }));
      fireEvent.click(screen.getByRole("button", { name: "Collapse all" }));
      expectRootsInView(tree, canvas);
    });
  });
});
