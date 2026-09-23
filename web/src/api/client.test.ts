import { describe, expect, it, vi } from "vitest";
import { createWorkspaceApiClient, fetchWorkspaceSnapshot } from "./client";
import { readApiConfig } from "./config";
import { ApiError, isRetryable } from "./errors";
import {
  createRecordedTransport,
  itemFixture,
  snapshotRecording,
  workspaceFixture,
} from "../test/recordedTransport";

const config = readApiConfig({
  VITE_VECTIS_WORKSPACE_KEY: "VEC",
  VITE_VECTIS_API_BASE_URL: "https://tracker.example.test/api/v1",
});

function clientFor(recording = snapshotRecording()) {
  const recorded = createRecordedTransport(recording);
  return {
    recorded,
    client: createWorkspaceApiClient({ config, transport: recorded.transport }),
  };
}

describe("WorkspaceApiClient", () => {
  it("addresses the workspace, board and item reads under the configured base URL", async () => {
    const { client, recorded } = clientFor();
    await fetchWorkspaceSnapshot(client);

    expect(recorded.calls.sort()).toEqual([
      "https://tracker.example.test/api/v1/workspaces/VEC",
      "https://tracker.example.test/api/v1/workspaces/VEC/boards",
      "https://tracker.example.test/api/v1/workspaces/VEC/items",
    ]);
  });

  it("decodes the wire payload into the store snapshot", async () => {
    const { client } = clientFor();
    const snapshot = await fetchWorkspaceSnapshot(client);

    expect(snapshot.boards.map((b) => b.id)).toEqual(["board-1"]);
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
      routes: { "/items": { body: [{ ...itemFixture(), rank: 7 }] } },
    });

    const error = await client.listItems().catch((e: unknown) => e);
    expect((error as ApiError).kind).toBe("format");
    expect((error as Error).message).toContain("items[0].rank");
    expect(isRetryable(error)).toBe(false);
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
