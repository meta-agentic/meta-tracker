import type { WorkspaceSnapshot } from "../store/types";
import { toWorkspaceSnapshot } from "./adapter";
import { readApiConfig, type ApiConfig, type ApiEnv } from "./config";
import { ApiError } from "./errors";
import { createHttpClient, type HttpClient, type Transport } from "./http";
import {
  decodeBoards,
  decodeConfiguration,
  decodeItem,
  decodeItemPage,
  decodeSprints,
  decodeWorkspace,
  decodeWorkspaces,
  type WireBoard,
  type WireConfiguration,
  type WireItem,
  type WireItemPage,
  type WireSprint,
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
  readonly workspaceKey: string;
  getWorkspace(signal?: AbortSignal): Promise<WireWorkspace>;
  listBoards(signal?: AbortSignal): Promise<WireBoard[]>;
  /** One page, newest first; `cursor` is the `next` of the page before. */
  getItemPage(cursor: string | null, signal?: AbortSignal): Promise<WireItemPage>;
  /** Every item, following `next` until the last page. */
  listItems(signal?: AbortSignal): Promise<WireItem[]>;
  /** The workspace's sprints as the server lists them: oldest first. */
  listSprints(signal?: AbortSignal): Promise<WireSprint[]>;
  /** The keys of the items in one sprint, following `next` until the last page. */
  listSprintItemKeys(sprintKey: string, signal?: AbortSignal): Promise<string[]>;
  /** The effective configuration, which the item forms are drawn from (VEC-69, VEC-70). */
  getConfiguration(signal?: AbortSignal): Promise<WireConfiguration>;
  /** One item, with the version a write to it takes back. */
  getItem(itemKey: string, signal?: AbortSignal): Promise<WireItem>;
  /** VEC-16's writes: each answers the item as written, or throws an `ApiError` carrying the problem. */
  createItem(body: ItemWrite & { column?: string; board?: string }): Promise<WireItem>;
  editItem(itemKey: string, version: number, body: ItemWrite): Promise<WireItem>;
  moveItem(itemKey: string, version: number, body: { column: string; fields?: Record<string, unknown> }): Promise<WireItem>;
}

/** A write's body: the title, and a JSON merge patch of the open fields. */
export interface ItemWrite {
  title?: string;
  fields?: Record<string, unknown>;
}

/**
 * The read above any one workspace — which workspaces the server holds — and a
 * client for the one the user opens. The workspace is chosen at run time, so one
 * bundle serves any deployment.
 */
export interface ApiClient {
  readonly config: ApiConfig;
  /** Every workspace on the server, in the server's order (newest first). */
  listWorkspaces(signal?: AbortSignal): Promise<WireWorkspace[]>;
  /** A client for one workspace, over the same transport. */
  workspace(workspaceKey: string): WorkspaceApiClient;
}

export interface ApiClientOptions {
  config: ApiConfig;
  /** Injected by the suite; defaults to `fetch` through `createHttpClient`. */
  transport?: Transport;
  /** Overrides the HTTP layer wholesale. Used only by tests of the client itself. */
  http?: HttpClient;
  /** Defaults to `ITEM_PAGE_SIZE`; the suite lowers it to page small recordings. */
  pageSize?: number;
}

export interface ClientOptions extends ApiClientOptions {
  workspaceKey: string;
}

function httpFor(options: ApiClientOptions): HttpClient {
  return (
    options.http ??
    createHttpClient({
      baseUrl: options.config.baseUrl,
      timeoutMs: options.config.timeoutMs,
      transport: options.transport,
    })
  );
}

export function createApiClient(options: ApiClientOptions): ApiClient {
  const http = httpFor(options);
  return {
    config: options.config,
    async listWorkspaces(signal) {
      return decodeWorkspaces(await http.getJson("/workspaces", signal));
    },
    workspace: (workspaceKey) => createWorkspaceApiClient({ ...options, http, workspaceKey }),
  };
}

export function createWorkspaceApiClient(options: ClientOptions): WorkspaceApiClient {
  const { config, workspaceKey } = options;
  const http = httpFor(options);

  const workspacePath = `/workspaces/${encodeURIComponent(workspaceKey)}`;
  const pageSize = options.pageSize ?? ITEM_PAGE_SIZE;

  async function getItemPage(cursor: string | null, signal?: AbortSignal, sprintKey?: string) {
    const query = new URLSearchParams({ limit: String(pageSize) });
    if (sprintKey !== undefined) query.set("sprint", sprintKey);
    if (cursor !== null) query.set("cursor", cursor);
    return decodeItemPage(await http.getJson(`${workspacePath}/items?${query}`, signal));
  }

  async function listAll(signal?: AbortSignal, sprintKey?: string): Promise<WireItem[]> {
    const items: WireItem[] = [];
    const seen = new Set<string>();
    let cursor: string | null = null;
    do {
      const page: WireItemPage = await getItemPage(cursor, signal, sprintKey);
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
  }

  return {
    config,
    workspaceKey,
    async getWorkspace(signal) {
      return decodeWorkspace(await http.getJson(workspacePath, signal));
    },
    async listBoards(signal) {
      return decodeBoards(await http.getJson(`${workspacePath}/boards`, signal));
    },
    getItemPage: (cursor, signal) => getItemPage(cursor, signal),
    listItems: (signal) => listAll(signal),
    async listSprints(signal) {
      return decodeSprints(await http.getJson(`${workspacePath}/sprints`, signal));
    },
    async listSprintItemKeys(sprintKey, signal) {
      return (await listAll(signal, sprintKey)).map((item) => item.key);
    },
    async getConfiguration(signal) {
      return decodeConfiguration(await http.getJson(`${workspacePath}/configuration`, signal));
    },
    async getItem(itemKey, signal) {
      return decodeItem(await http.getJson(`${workspacePath}/items/${encodeURIComponent(itemKey)}`, signal));
    },
    async createItem(body) {
      return decodeItem(await http.sendJson("POST", `${workspacePath}/items`, body));
    },
    async editItem(itemKey, version, body) {
      return decodeItem(await http.sendJson("PATCH", `${workspacePath}/items/${encodeURIComponent(itemKey)}`, body, version));
    },
    async moveItem(itemKey, version, body) {
      return decodeItem(
        await http.sendJson("POST", `${workspacePath}/items/${encodeURIComponent(itemKey)}/move`, body, version),
      );
    },
  };
}

/** Builds a client from the build-time environment. Throws `ApiError("config")`. */
export function createApiClientFromEnv(env: ApiEnv, transport?: Transport): ApiClient {
  return createApiClient({ config: readApiConfig(env), transport });
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
