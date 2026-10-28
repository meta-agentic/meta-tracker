import type { WorkspaceSnapshot } from "../store/types";
import { toWorkspaceSnapshot } from "./adapter";
import { readApiConfig, type ApiConfig, type ApiEnv } from "./config";
import { ApiError } from "./errors";
import { createHttpClient, type HttpClient, type Transport } from "./http";
import {
  decodeBoards,
  decodeItemPage,
  decodeWorkspace,
  type WireBoard,
  type WireItem,
  type WireItemPage,
  type WireWorkspace,
} from "./wire";

/** Items per page: the server's maximum, so a large workspace costs the fewest round trips. */
export const ITEM_PAGE_SIZE = 200;

/**
 * The read surface of the workspace API.
 *
 * Every method returns wire types, never store types: the translation is
 * `adapter.ts`'s job and stays outside the transport. `fetchWorkspaceSnapshot`
 * below composes the three reads into the store's shape, which is the only entry
 * point the application needs.
 *
 * The paths are the VEC-94 reads under `/api/v1` (ADR-VEC-05): the workspace by
 * its key, its boards with their columns, and its items a page at a time.
 */
export interface WorkspaceApiClient {
  readonly config: ApiConfig;
  getWorkspace(signal?: AbortSignal): Promise<WireWorkspace>;
  listBoards(signal?: AbortSignal): Promise<WireBoard[]>;
  /** One page, newest first; `cursor` is the `next` of the page before. */
  getItemPage(cursor: string | null, signal?: AbortSignal): Promise<WireItemPage>;
  /** Every item, following `next` until the last page. */
  listItems(signal?: AbortSignal): Promise<WireItem[]>;
}

export interface ClientOptions {
  config: ApiConfig;
  /** Injected by the suite; defaults to `fetch` through `createHttpClient`. */
  transport?: Transport;
  /** Overrides the HTTP layer wholesale. Used only by tests of the client itself. */
  http?: HttpClient;
  /** Defaults to `ITEM_PAGE_SIZE`; the suite lowers it to page small recordings. */
  pageSize?: number;
}

export function createWorkspaceApiClient(options: ClientOptions): WorkspaceApiClient {
  const { config } = options;
  const http =
    options.http ??
    createHttpClient({
      baseUrl: config.baseUrl,
      timeoutMs: config.timeoutMs,
      transport: options.transport,
    });

  const workspacePath = `/workspaces/${encodeURIComponent(config.workspaceKey)}`;
  const pageSize = options.pageSize ?? ITEM_PAGE_SIZE;

  async function getItemPage(cursor: string | null, signal?: AbortSignal) {
    const query = new URLSearchParams({ limit: String(pageSize) });
    if (cursor !== null) query.set("cursor", cursor);
    return decodeItemPage(await http.getJson(`${workspacePath}/items?${query}`, signal));
  }

  return {
    config,
    async getWorkspace(signal) {
      return decodeWorkspace(await http.getJson(workspacePath, signal));
    },
    async listBoards(signal) {
      return decodeBoards(await http.getJson(`${workspacePath}/boards`, signal));
    },
    getItemPage,
    async listItems(signal) {
      const items: WireItem[] = [];
      const seen = new Set<string>();
      let cursor: string | null = null;
      do {
        const page: WireItemPage = await getItemPage(cursor, signal);
        items.push(...page.items);
        cursor = page.next;
        // A cursor handed out twice would page forever; the server never does it,
        // so one that does is off-contract.
        if (cursor !== null && seen.has(cursor)) {
          throw new ApiError("format", `items: the cursor "${cursor}" was returned twice`);
        }
        if (cursor !== null) seen.add(cursor);
      } while (cursor !== null);
      return items;
    },
  };
}

/** Builds a client from the build-time environment. Throws `ApiError("config")`. */
export function createWorkspaceApiClientFromEnv(
  env: ApiEnv,
  transport?: Transport,
): WorkspaceApiClient {
  return createWorkspaceApiClient({ config: readApiConfig(env), transport });
}

/**
 * One workspace snapshot, ready for `ingestSnapshot`.
 *
 * The three reads are issued together rather than in sequence: they are
 * independent, and a serial chain would make a cold load three round-trips deep
 * for no reason. The item pages behind the first one are necessarily serial,
 * each needing the cursor of the one before. `Promise.all` rejects on the first failure, which is what the
 * caller wants — a partial snapshot is not a snapshot.
 */
export async function fetchWorkspaceSnapshot(
  client: WorkspaceApiClient,
  signal?: AbortSignal,
): Promise<WorkspaceSnapshot> {
  const [workspace, boards, items] = await Promise.all([
    client.getWorkspace(signal),
    client.listBoards(signal),
    client.listItems(signal),
  ]);
  return toWorkspaceSnapshot({ workspace, boards, items });
}
