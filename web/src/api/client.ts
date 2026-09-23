import type { WorkspaceSnapshot } from "../store/types";
import { toWorkspaceSnapshot } from "./adapter";
import { readApiConfig, type ApiConfig, type ApiEnv } from "./config";
import { createHttpClient, type HttpClient, type Transport } from "./http";
import {
  decodeBoards,
  decodeItems,
  decodeWorkspace,
  type WireBoard,
  type WireItem,
  type WireWorkspace,
} from "./wire";

/**
 * The read surface of the workspace API.
 *
 * Every method returns wire types, never store types: the translation is
 * `adapter.ts`'s job and stays outside the transport. `fetchWorkspaceSnapshot`
 * below composes the three reads into the store's shape, which is the only entry
 * point the application needs.
 *
 * The paths mirror the aggregates in `vectis-domain` — a workspace, its boards,
 * its items. VEC-10 owns the endpoint contract and has not landed, so this is a
 * derivation from the server model rather than a published specification; see
 * the module docs in `adapter.ts` for the assumptions it rests on.
 */
export interface WorkspaceApiClient {
  readonly config: ApiConfig;
  getWorkspace(signal?: AbortSignal): Promise<WireWorkspace>;
  listBoards(signal?: AbortSignal): Promise<WireBoard[]>;
  listItems(signal?: AbortSignal): Promise<WireItem[]>;
}

export interface ClientOptions {
  config: ApiConfig;
  /** Injected by the suite; defaults to `fetch` through `createHttpClient`. */
  transport?: Transport;
  /** Overrides the HTTP layer wholesale. Used only by tests of the client itself. */
  http?: HttpClient;
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

  return {
    config,
    async getWorkspace(signal) {
      return decodeWorkspace(await http.getJson(workspacePath, signal));
    },
    async listBoards(signal) {
      return decodeBoards(await http.getJson(`${workspacePath}/boards`, signal));
    },
    async listItems(signal) {
      return decodeItems(await http.getJson(`${workspacePath}/items`, signal));
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
 * for no reason. `Promise.all` rejects on the first failure, which is what the
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
