import { createWorkspaceApiClient, readApiConfig, type Transport, type WorkspaceSyncOptions } from "../api";
import type { WireBoard, WireItem, WireJsonValue, WireWorkspace } from "../api/wire";

/**
 * DEVELOPMENT ONLY — a canned workspace served over the real client.
 *
 * Lets the SPA be looked at with no server running. `main.tsx` reaches this
 * module only behind `import.meta.env.DEV`, which Vite replaces with `false` in
 * a production build, so the branch and this whole module are dropped from the
 * bundle. Enable with `VITE_VECTIS_FIXTURES=true` in `.env.local`, then pick a
 * scenario with `?fixture=`:
 *
 *   ready (default)  the workspace below
 *   slow             the same, 2.5 s late — the loading and refreshing states
 *   error            every request fails — the error state on a cold cache,
 *                    the stale banner on a warm one
 *   empty            a workspace with no boards
 *
 * Only the transport is fake: the requests still go through the real client,
 * the real decoders and the real adapter, so what renders is what the server's
 * payload would render.
 */

const workspace: WireWorkspace = { key: "ORB", name: "Orbit", template: { key: "scrum", version: 1 } };

const columns = [
  { key: "backlog", name: "Backlog", ordinal: 0, onBoard: true },
  { key: "todo", name: "To Do", ordinal: 1, onBoard: true },
  { key: "doing", name: "In Progress", ordinal: 2, onBoard: true },
  { key: "review", name: "In Review", ordinal: 3, onBoard: true },
  { key: "done", name: "Done", ordinal: 4, onBoard: true },
];

const boards: WireBoard[] = [
  { key: "ORB", name: "Product", columns },
  { key: "ORB-2", name: "Platform", columns },
];

let sequence = 0;

function item(
  board: string,
  column: string,
  key: string,
  title: string,
  fields: Record<string, WireJsonValue>,
): WireItem {
  sequence += 1;
  return {
    key,
    title,
    board,
    column,
    rank: String(sequence).padStart(4, "0"),
    fields,
    version: 1,
  };
}

const P = "ORB";
const F = "ORB-2";

const items: WireItem[] = [
  item(P, "backlog", "ORB-1", "Onboarding flow", { type: "epic" }),
  item(P, "backlog", "ORB-2", "Offline sync", { type: "epic" }),
  item(F, "backlog", "ORB-3", "Billing v2", { type: "epic" }),

  item(P, "backlog", "ORB-11", "Let people skip the tour and come back to it later", {
    type: "story", parentId: "ORB-1", storyPoints: 3, labels: ["ux"], priority: "P2",
  }),
  item(P, "backlog", "ORB-12", "Explore conflict-free merge for offline edits", {
    type: "spike", parentId: "ORB-2", storyPoints: 5, labels: ["research", "sync"],
    description: "Time-boxed to two days. Compare last-writer-wins with a per-field merge and report which conflicts each one loses.",
  }),
  item(P, "todo", "ORB-13", "Invite teammates from the welcome screen", {
    type: "story", parentId: "ORB-1", storyPoints: 5, labels: ["ux", "growth"], priority: "P1",
    description: "As a new workspace owner, I want to invite my team without leaving the welcome screen, so that the workspace is useful on day one.\n\nAcceptance:\n- Invite by email, several at once\n- Invites show as pending until accepted",
    dependencies: ["ORB-21"], relates: ["ORB-11"], startDate: "2026-10-05", dueDate: "2026-10-16",
  }),
  item(P, "todo", "ORB-14", "Queue writes while offline and replay them in order", {
    type: "story", parentId: "ORB-2", storyPoints: 8, labels: ["sync"], priority: "P1",
    dependencies: ["ORB-12"],
  }),
  item(P, "todo", "ORB-15", "Empty states for the first project", {
    type: "task", parentId: "ORB-1", storyPoints: 2, labels: ["ux", "copy", "design", "a11y"],
  }),
  item(P, "doing", "ORB-16", "Sign-in link expires before the email arrives", {
    type: "bug", storyPoints: 2, labels: ["auth"], priority: "P0",
    description: "Magic links are valid for five minutes, but the mail provider queues messages for up to ten under load. Extend the validity and show a clear message when a link has expired.",
    relates: ["ORB-21"],
  }),
  item(P, "doing", "ORB-17", "Progress checklist on the home screen", {
    type: "story", parentId: "ORB-1", storyPoints: 3, labels: ["ux"],
  }),
  item(P, "review", "ORB-18", "Show a sync badge when changes are waiting to upload", {
    type: "story", parentId: "ORB-2", storyPoints: 3, labels: ["sync", "ux"],
    relates: ["ORB-14"],
  }),
  item(P, "done", "ORB-19", "Welcome email with a single call to action", {
    type: "task", parentId: "ORB-1", storyPoints: 1, labels: ["copy"],
  }),
  item(P, "done", "ORB-20", "Crash when rotating the device during sign-up", {
    type: "bug", storyPoints: 1, labels: ["mobile"],
  }),

  item(F, "todo", "ORB-21", "Transactional email service with retries", {
    type: "enabler", storyPoints: 5, labels: ["infra"], priority: "P1",
    description: "One service every product email goes through, with retry and a dead-letter queue, so no flow sends mail on its own.",
  }),
  item(F, "todo", "ORB-22", "Proration when a plan changes mid-cycle", {
    type: "story", parentId: "ORB-3", storyPoints: 8, labels: ["billing"],
  }),
  item(F, "doing", "ORB-23", "Rate-limit the public API per workspace", {
    type: "task", storyPoints: 3, labels: ["infra", "security"],
  }),
  item(F, "review", "ORB-24", "Invoice PDF renders the wrong currency symbol", {
    type: "bug", parentId: "ORB-3", storyPoints: 2, labels: ["billing"], priority: "P1",
  }),
  item(F, "done", "ORB-25", "Move background jobs to the shared queue", {
    type: "enabler", storyPoints: 5, labels: ["infra"],
  }),
];

type Scenario = "ready" | "slow" | "error" | "empty";

function scenarioFromUrl(): Scenario {
  const value = new URLSearchParams(window.location.search).get("fixture");
  return value === "slow" || value === "error" || value === "empty" ? value : "ready";
}

function json(body: unknown): Response {
  return new Response(JSON.stringify(body), {
    status: 200,
    headers: { "content-type": "application/json" },
  });
}

function fixtureTransport(scenario: Scenario): Transport {
  return async (url) => {
    if (scenario === "slow") await new Promise((resolve) => setTimeout(resolve, 2500));
    if (scenario === "error") throw new TypeError("Failed to fetch");
    const empty = scenario === "empty";
    const path = url.split("?")[0];
    if (path.endsWith("/boards")) return json(empty ? [] : boards);
    if (path.endsWith("/items")) return json({ items: empty ? [] : items, next: null });
    if (path.endsWith(`/workspaces/${workspace.key}`)) return json(workspace);
    return new Response("", { status: 404, statusText: "Not Found" });
  };
}

export function fixtureSyncOptions(): WorkspaceSyncOptions {
  const scenario = scenarioFromUrl();
  const config = readApiConfig({ VITE_VECTIS_WORKSPACE_KEY: workspace.key });
  return {
    createClient: () =>
      createWorkspaceApiClient({ config, transport: fixtureTransport(scenario) }),
  };
}
