import type { Transport } from "../api/http";
import type { WireBoard, WireItem, WireItemPage, WireWorkspace } from "../api/wire";

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
  /** Defaults to `application/json`. */
  contentType?: string;
}

export interface Recording {
  /**
   * Path suffixes, matched by `endsWith`, so the base URL stays irrelevant. A
   * route without a `?` ignores the query string; one with a `?` must match it.
   */
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

    const bare = url.split("?")[0];
    const routes = Object.entries(recording.routes);
    // A route naming its query wins over the bare path, so a recording can hold
    // a first page and the pages behind it side by side.
    const match =
      routes.find(([path]) => path.includes("?") && url.endsWith(path)) ??
      routes.find(([path]) => !path.includes("?") && bare.endsWith(path));
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
        headers: { "content-type": recorded.contentType ?? "application/json" },
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

/** `GET /workspaces/VEC`, as `WorkspacesResource` answers it. */
export const workspaceFixture: WireWorkspace = {
  key: "VEC",
  name: "Vectis",
  template: { key: "scrum", version: 1 },
};

/** The workspace's first board, which takes the workspace key. Columns out of ordinal order. */
export const boardFixture: WireBoard = {
  key: "VEC",
  name: "Delivery",
  columns: [
    { key: "doing", name: "In Progress", ordinal: 1, onBoard: true },
    { key: "todo", name: "To Do", ordinal: 0, onBoard: true },
  ],
};

export const epicItemFixture: WireItem = {
  key: "VEC-1",
  title: "Client shell",
  board: boardFixture.key,
  column: "todo",
  rank: "a",
  fields: { type: "epic", color: "#123456" },
  version: 1,
};

export function itemFixture(overrides: Partial<WireItem> = {}): WireItem {
  return {
    key: "VEC-2",
    title: "Wire the client",
    board: boardFixture.key,
    column: "todo",
    rank: "m",
    fields: {},
    version: 1,
    ...overrides,
  };
}

/** One page of items, the last unless `next` is given. */
export function itemPage(items: WireItem[], next: string | null = null): WireItemPage {
  return { items, next };
}

/** The problem+json answer the server gives for an unknown workspace (ADR-VEC-05). */
export function workspaceNotFound(key: string): RecordedResponse {
  return {
    status: 404,
    statusText: "Not Found",
    contentType: "application/problem+json",
    body: {
      type: "about:blank",
      title: "Not Found",
      status: 404,
      detail: `workspace ${key} does not exist`,
    },
  };
}

/** The three-route recording a healthy snapshot fetch replays. */
export function snapshotRecording(items: WireItem[] = [itemFixture()]): Recording {
  return {
    routes: {
      "/boards": { body: [boardFixture] },
      "/items": { body: itemPage(items) },
      "/workspaces/VEC": { body: workspaceFixture },
    },
  };
}
