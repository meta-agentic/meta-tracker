import { createContext, useContext } from "react";
import type { WorkspaceApiClient } from "../api";
import type { Effective } from "./rules";

/**
 * What the item forms need from the open workspace: its client, its effective
 * configuration (what the forms are drawn from) and whether a client may write
 * to it at all. Loaded once per workspace, beside the snapshot the board reads.
 */
export interface ItemWrites {
  client: WorkspaceApiClient;
  /** Null until it has loaded, or if it could not be. */
  configuration: Effective | null;
  /** Null until known. True for a workspace bound to a backlog source (VEC-16): no write controls. */
  readOnly: boolean | null;
  /** Re-reads the workspace after a write, so the board shows it. */
  refresh: () => void;
}

export const ItemWritesContext = createContext<ItemWrites | null>(null);

/** The open workspace's writes, or null outside a workspace (the profiler, a test without one). */
export function useItemWrites(): ItemWrites | null {
  return useContext(ItemWritesContext);
}
