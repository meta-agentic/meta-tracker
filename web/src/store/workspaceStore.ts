import { createStore, type StoreApi } from "zustand/vanilla";
import { useStore } from "zustand";
import {
  createDefaultCache,
  uiTracking,
  type AsyncCache,
} from "./cache";
import type { Board, Epic, ID, Issue, WorkspaceSnapshot } from "./types";

const CACHE_KEY = "workspace:v1";
const ACTIVE_BOARD_KEY = "ui:activeBoardId";

/**
 * Fully flat, normalized state. Every entity is addressed by id in a plain map,
 * so a lookup is O(1) and switching the active board is a pointer change rather
 * than a re-fetch or a re-shape of nested arrays. `boardIssueIds` is a
 * denormalized index (board -> ordered issue ids) so a board's column layout is
 * assembled without scanning every issue.
 */
export interface WorkspaceState {
  issuesById: Record<ID, Issue>;
  epicsById: Record<ID, Epic>;
  boardsById: Record<ID, Board>;
  boardIssueIds: Record<ID, ID[]>;
  activeBoardId: ID | null;
  hydrated: boolean;

  ingestSnapshot: (snapshot: WorkspaceSnapshot) => void;
  upsertIssue: (issue: Issue) => void;
  moveIssue: (issueId: ID, columnId: ID, order: number) => void;
  setActiveBoard: (boardId: ID | null) => void;
  hydrate: () => Promise<void>;
  reset: () => void;
}

interface NormalizedData {
  issuesById: Record<ID, Issue>;
  epicsById: Record<ID, Epic>;
  boardsById: Record<ID, Board>;
  boardIssueIds: Record<ID, ID[]>;
}

function indexById<T extends { id: ID }>(items: T[]): Record<ID, T> {
  const out: Record<ID, T> = {};
  for (const item of items) out[item.id] = item;
  return out;
}

function buildBoardIssueIndex(issues: Issue[]): Record<ID, ID[]> {
  const index: Record<ID, ID[]> = {};
  for (const issue of issues) (index[issue.boardId] ??= []).push(issue.id);
  return index;
}

const empty: NormalizedData = {
  issuesById: {},
  epicsById: {},
  boardsById: {},
  boardIssueIds: {},
};

export function createWorkspaceStore(
  cache: AsyncCache = createDefaultCache(),
): StoreApi<WorkspaceState> {
  let persistTimer: ReturnType<typeof setTimeout> | undefined;

  const store = createStore<WorkspaceState>((set, get) => {
    const schedulePersist = () => {
      if (persistTimer) clearTimeout(persistTimer);
      persistTimer = setTimeout(() => {
        const { issuesById, epicsById, boardsById, boardIssueIds } = get();
        void cache.set<NormalizedData>(CACHE_KEY, {
          issuesById,
          epicsById,
          boardsById,
          boardIssueIds,
        });
      }, 250);
    };

    return {
      ...empty,
      activeBoardId: null,
      hydrated: false,

      ingestSnapshot: (snapshot) => {
        set({
          issuesById: indexById(snapshot.issues),
          epicsById: indexById(snapshot.epics),
          boardsById: indexById(snapshot.boards),
          boardIssueIds: buildBoardIssueIndex(snapshot.issues),
        });
        schedulePersist();
      },

      upsertIssue: (issue) => {
        const state = get();
        const existing = state.issuesById[issue.id];
        const issuesById = { ...state.issuesById, [issue.id]: issue };
        let boardIssueIds = state.boardIssueIds;
        if (!existing || existing.boardId !== issue.boardId) {
          boardIssueIds = { ...boardIssueIds };
          if (existing) {
            boardIssueIds[existing.boardId] = (
              boardIssueIds[existing.boardId] ?? []
            ).filter((id) => id !== issue.id);
          }
          boardIssueIds[issue.boardId] = [
            ...(boardIssueIds[issue.boardId] ?? []),
            issue.id,
          ];
        }
        set({ issuesById, boardIssueIds });
        schedulePersist();
      },

      moveIssue: (issueId, columnId, order) => {
        const existing = get().issuesById[issueId];
        if (!existing) return;
        set((state) => ({
          issuesById: {
            ...state.issuesById,
            [issueId]: { ...existing, columnId, order },
          },
        }));
        schedulePersist();
      },

      setActiveBoard: (boardId) => {
        set({ activeBoardId: boardId });
        uiTracking.write(ACTIVE_BOARD_KEY, boardId);
      },

      hydrate: async () => {
        const cached = await cache.get<NormalizedData>(CACHE_KEY);
        const activeBoardId = uiTracking.read<ID | null>(
          ACTIVE_BOARD_KEY,
          null,
        );
        set({
          ...(cached ?? empty),
          activeBoardId,
          hydrated: true,
        });
      },

      reset: () => {
        if (persistTimer) clearTimeout(persistTimer);
        set({ ...empty, activeBoardId: null, hydrated: false });
        void cache.delete(CACHE_KEY);
      },
    };
  });

  return store;
}

export const workspaceStore = createWorkspaceStore();

export function useWorkspaceStore<T>(selector: (state: WorkspaceState) => T): T {
  return useStore(workspaceStore, selector);
}
