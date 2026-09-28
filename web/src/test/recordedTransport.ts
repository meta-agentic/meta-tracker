import type { Transport } from "../api/http";
import type { WireBoard, WireItem, WireWorkspace } from "../api/wire";

/**
 * A recorded transport: a fixed map of path to response, replayed in place of
 * the network.
 *
 * The suite exercises the real client — the real headers, the real decoders, the
 * real adapter — and swaps only the one function that would otherwise open a
 * socket. Nothing here starts a server, so `npm run test` passes with nothing
 * listening on the API port.
 */

export interface RecordedResponse {
  status?: number;
  statusText?: string;
  /** Serialized as the JSON body. Mutually exclusive with `rawBody`. */
  body?: unknown;
  /** Returned verbatim, for testing a non-JSON body. */
  rawBody?: string;
}

export interface Recording {
  /** Path suffixes, matched by `endsWith`, so the base URL stays irrelevant. */
  routes: Record<string, RecordedResponse>;
  /** Thrown instead of answering — a connection that never landed. */
  networkError?: Error;
  /** Never settles, so a timeout can be observed. */
  hang?: boolean;
  /**
   * Awaited before any response is produced. A test that must observe the state
   * of the client *while* a request is in flight releases it by hand, instead of
   * racing an immediately-resolved promise.
   */
  gate?: Promise<void>;
}

export interface Gate {
  promise: Promise<void>;
  release: () => void;
}

export function createGate(): Gate {
  let release = () => {};
  const promise = new Promise<void>((resolve) => {
    release = resolve;
  });
  return { promise, release };
}

export interface RecordedTransport {
  transport: Transport;
  /** Every URL requested, in order. */
  readonly calls: string[];
  /** Swaps the recording mid-test, for revalidation scenarios. */
  play(next: Recording): void;
}

export function createRecordedTransport(initial: Recording): RecordedTransport {
  let recording = initial;
  const calls: string[] = [];

  const transport: Transport = async (url, init) => {
    calls.push(url);

    if (recording.gate) await recording.gate;

    if (recording.hang) {
      return new Promise((_resolve, reject) => {
        init.signal?.addEventListener("abort", () => reject(new Error("aborted")), {
          once: true,
        });
      });
    }

    if (recording.networkError) return Promise.reject(recording.networkError);

    const match = Object.entries(recording.routes).find(([path]) => url.endsWith(path));
    if (!match) {
      return Promise.resolve(
        new Response("", { status: 404, statusText: "Not Found" }),
      );
    }

    const [, recorded] = match;
    const body =
      recorded.rawBody ?? (recorded.body === undefined ? "" : JSON.stringify(recorded.body));
    return Promise.resolve(
      new Response(body, {
        status: recorded.status ?? 200,
        statusText: recorded.statusText ?? "OK",
        headers: { "content-type": "application/json" },
      }),
    );
  };

  return {
    transport,
    calls,
    play(next) {
      recording = next;
    },
  };
}

/** A workspace matching `io.vectis.domain.Workspace`. */
export const workspaceFixture: WireWorkspace = {
  id: "0192f3a0-0000-7000-8000-000000000001",
  key: "VEC",
  name: "Vectis",
};

export const boardFixture: WireBoard = {
  id: "board-1",
  workspaceId: workspaceFixture.id,
  name: "Delivery",
  columns: [
    { id: "col-doing", name: "In Progress", position: 1 },
    { id: "col-todo", name: "To Do", position: 0 },
  ],
};

export const epicItemFixture: WireItem = {
  id: "epic-1",
  workspaceId: workspaceFixture.id,
  boardId: boardFixture.id,
  columnId: "col-todo",
  key: "VEC-1",
  title: "Client shell",
  rank: "a",
  fields: { type: "epic", color: "#123456" },
  sprintId: null,
};

export function itemFixture(overrides: Partial<WireItem> = {}): WireItem {
  return {
    id: "item-1",
    workspaceId: workspaceFixture.id,
    boardId: boardFixture.id,
    columnId: "col-todo",
    key: "VEC-2",
    title: "Wire the client",
    rank: "m",
    fields: {},
    sprintId: null,
    ...overrides,
  };
}

/** The three-route recording a healthy snapshot fetch replays. */
export function snapshotRecording(items: WireItem[] = [itemFixture()]): Recording {
  return {
    routes: {
      "/boards": { body: [boardFixture] },
      "/items": { body: items },
      "/workspaces/VEC": { body: workspaceFixture },
    },
  };
}
