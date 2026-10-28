import { describe, expect, it, vi } from "vitest";
import { createWorkspaceApiClient, fetchWorkspaceSnapshot } from "./client";
import { readApiConfig } from "./config";
import { ApiError, isRetryable } from "./errors";
import {
  boardFixture,
  createRecordedTransport,
  itemFixture,
  itemPage,
  snapshotRecording,
  workspaceFixture,
  workspaceNotFound,
  type Recording,
} from "../test/recordedTransport";

const config = readApiConfig({
  VITE_VECTIS_WORKSPACE_KEY: "VEC",
  VITE_VECTIS_API_BASE_URL: "https://tracker.example.test/api/v1",
});

function clientFor(recording: Recording = snapshotRecording(), pageSize?: number) {
  const recorded = createRecordedTransport(recording);
  return {
    recorded,
    client: createWorkspaceApiClient({ config, transport: recorded.transport, pageSize }),
  };
}

const base = "https://tracker.example.test/api/v1/workspaces/VEC";

describe("WorkspaceApiClient", () => {
  it("addresses the workspace, board and item reads under the configured base URL", async () => {
    const { client, recorded } = clientFor();
    await fetchWorkspaceSnapshot(client);

    expect(recorded.calls.sort()).toEqual([
      "https://tracker.example.test/api/v1/workspaces/VEC",
      "https://tracker.example.test/api/v1/workspaces/VEC/boards",
      "https://tracker.example.test/api/v1/workspaces/VEC/items?limit=200",
    ]);
  });

  it("decodes the wire payload into the store snapshot", async () => {
    const { client } = clientFor();
    const snapshot = await fetchWorkspaceSnapshot(client);

    expect(snapshot.boards.map((b) => b.id)).toEqual(["VEC"]);
    expect(snapshot.issues.map((i) => i.key)).toEqual(["VEC-2"]);
    expect(snapshot.epics).toEqual([]);
  });

  it("returns the wire workspace unchanged from the read endpoint", async () => {
    const { client } = clientFor();
    await expect(client.getWorkspace()).resolves.toEqual(workspaceFixture);
  });

  it("reports a non-2xx response as a retryable-or-not http error", async () => {
    const { client } = clientFor({
      routes: { "/workspaces/VEC": { status: 503, statusText: "Service Unavailable" } },
    });

    const error = await client.getWorkspace().catch((e: unknown) => e);
    expect(error).toBeInstanceOf(ApiError);
    expect((error as ApiError).kind).toBe("http");
    expect((error as ApiError).status).toBe(503);
    expect(isRetryable(error)).toBe(true);
  });

  it("does not offer a retry for a 404", async () => {
    const { client } = clientFor({ routes: {} });
    const error = await client.listBoards().catch((e: unknown) => e);
    expect((error as ApiError).status).toBe(404);
    expect(isRetryable(error)).toBe(false);
  });

  it("reports an unreachable server as a network error", async () => {
    const { client } = clientFor({
      routes: {},
      networkError: new TypeError("Failed to fetch"),
    });

    const error = await client.getWorkspace().catch((e: unknown) => e);
    expect((error as ApiError).kind).toBe("network");
    expect(isRetryable(error)).toBe(true);
  });

  it("names the offending path when the body drifts from the contract", async () => {
    const { client } = clientFor({
      routes: { "/items": { body: itemPage([{ ...itemFixture(), rank: 7 } as never]) } },
    });

    const error = await client.listItems().catch((e: unknown) => e);
    expect((error as ApiError).kind).toBe("format");
    expect((error as Error).message).toContain("items.items[0].rank");
    expect(isRetryable(error)).toBe(false);
  });

  it("refuses the old shapes: a column by id and position, an item by column id, a bare array", async () => {
    const oldColumn = { id: "col-todo", name: "To Do", position: 0 };
    const { client } = clientFor({
      routes: {
        "/boards": { body: [{ ...boardFixture, columns: [oldColumn] }] },
        "/items": { body: [itemFixture()] },
      },
    });
    const boards = await client.listBoards().catch((e: unknown) => e);
    expect((boards as Error).message).toContain("boards[0].columns[0].key");

    const items = await client.listItems().catch((e: unknown) => e);
    expect((items as ApiError).kind).toBe("format");
    expect((items as Error).message).toContain("items: expected an object, received an array");

    const { client: other } = clientFor({
      routes: { "/items": { body: itemPage([{ ...itemFixture(), column: undefined, columnId: "todo" } as never]) } },
    });
    const columnId = await other.listItems().catch((e: unknown) => e);
    expect((columnId as Error).message).toContain("items.items[0].column");
  });

  it("decodes a column's key, name, ordinal and onBoard", async () => {
    const { client } = clientFor();
    const [board] = await client.listBoards();
    expect(board.columns).toEqual(boardFixture.columns);

    const { client: drifted } = clientFor({
      routes: { "/boards": { body: [{ ...boardFixture, columns: [{ key: "todo", name: "To Do", ordinal: 0 }] }] } },
    });
    const error = await drifted.listBoards().catch((e: unknown) => e);
    expect((error as Error).message).toContain("boards[0].columns[0].onBoard");
  });

  it("follows the cursor until the last page and keeps the server's newest-first order", async () => {
    const { client, recorded } = clientFor(
      {
        routes: {
          "/items?limit=2": { body: itemPage([itemFixture({ key: "VEC-5" }), itemFixture({ key: "VEC-4" })], "c1") },
          "/items?limit=2&cursor=c1": {
            body: itemPage([itemFixture({ key: "VEC-3" }), itemFixture({ key: "VEC-2" })], "c2"),
          },
          "/items?limit=2&cursor=c2": { body: itemPage([itemFixture({ key: "VEC-1" })]) },
        },
      },
      2,
    );

    const items = await client.listItems();
    expect(items.map((i) => i.key)).toEqual(["VEC-5", "VEC-4", "VEC-3", "VEC-2", "VEC-1"]);
    expect(recorded.calls).toEqual([
      `${base}/items?limit=2`,
      `${base}/items?limit=2&cursor=c1`,
      `${base}/items?limit=2&cursor=c2`,
    ]);
  });

  it("stops rather than loops on a cursor handed out twice", async () => {
    const { client } = clientFor(
      {
        routes: {
          "/items?limit=1": { body: itemPage([itemFixture({ key: "VEC-3" })], "c1") },
          "/items?limit=1&cursor=c1": { body: itemPage([itemFixture({ key: "VEC-2" })], "c1") },
        },
      },
      1,
    );
    const error = await client.listItems().catch((e: unknown) => e);
    expect((error as ApiError).kind).toBe("format");
    expect((error as Error).message).toContain("c1");
  });

  it("reads a page without next as off-contract, not as the last page", async () => {
    const { client } = clientFor({ routes: { "/items": { body: { items: [itemFixture()] } } } });
    const error = await client.listItems().catch((e: unknown) => e);
    expect((error as ApiError).kind).toBe("format");
    expect((error as Error).message).toContain("items.next");
  });

  it("decodes a problem+json answer into the error, with the server's sentence as its message", async () => {
    const { client } = clientFor({ routes: { "/workspaces/VEC": workspaceNotFound("VEC") } });

    const error = (await client.getWorkspace().catch((e: unknown) => e)) as ApiError;
    expect(error).toBeInstanceOf(ApiError);
    expect(error.kind).toBe("http");
    expect(error.status).toBe(404);
    expect(error.problem).toEqual({
      type: "about:blank",
      title: "Not Found",
      status: 404,
      detail: "workspace VEC does not exist",
    });
    expect(error.message).toBe("404 Not Found: workspace VEC does not exist");
    expect(isRetryable(error)).toBe(false);
  });

  it("leaves the status error standing when the error body is not a problem", async () => {
    const { client } = clientFor({
      routes: {
        "/workspaces/VEC": { status: 502, statusText: "Bad Gateway", body: { title: "Bad Gateway", status: 502 } },
      },
    });
    const error = (await client.getWorkspace().catch((e: unknown) => e)) as ApiError;
    expect(error.problem).toBeUndefined();
    expect(error.message).toContain("returned 502 Bad Gateway");

    const { client: malformed } = clientFor({
      routes: {
        "/workspaces/VEC": {
          status: 404,
          contentType: "application/problem+json",
          body: { detail: "no title or status" },
        },
      },
    });
    const fallback = (await malformed.getWorkspace().catch((e: unknown) => e)) as ApiError;
    expect(fallback.problem).toBeUndefined();
    expect(fallback.status).toBe(404);
  });

  it("rejects a non-JSON body rather than returning undefined", async () => {
    const { client } = clientFor({
      routes: { "/workspaces/VEC": { rawBody: "<html>gateway</html>" } },
    });

    const error = await client.getWorkspace().catch((e: unknown) => e);
    expect((error as ApiError).kind).toBe("format");
  });

  it("abandons a request that outlives the configured timeout", async () => {
    vi.useFakeTimers();
    try {
      const recorded = createRecordedTransport({ routes: {}, hang: true });
      const client = createWorkspaceApiClient({
        config: { ...config, timeoutMs: 50 },
        transport: recorded.transport,
      });

      const pending = client.getWorkspace().catch((e: unknown) => e);
      await vi.advanceTimersByTimeAsync(60);

      const error = await pending;
      expect((error as ApiError).kind).toBe("timeout");
      expect(isRetryable(error)).toBe(true);
    } finally {
      vi.useRealTimers();
    }
  });

  it("propagates a caller's abort as the caller's own error, not a transport failure", async () => {
    const recorded = createRecordedTransport({ routes: {}, hang: true });
    const client = createWorkspaceApiClient({ config, transport: recorded.transport });
    const controller = new AbortController();

    const pending = client.listItems(controller.signal).catch((e: unknown) => e);
    controller.abort();

    expect(await pending).not.toBeInstanceOf(ApiError);
  });

  it("issues the three reads concurrently rather than in a chain", async () => {
    const { client, recorded } = clientFor();
    const pending = fetchWorkspaceSnapshot(client);
    // All three URLs are requested before any response is awaited.
    expect(recorded.calls).toHaveLength(3);
    await pending;
  });
});
