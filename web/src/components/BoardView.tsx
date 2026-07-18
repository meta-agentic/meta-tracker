import { useMemo } from "react";
import { useWorkspaceStore } from "../store/workspaceStore";
import { boardColumns, selectActiveBoard } from "../store/selectors";

/**
 * Renders the active board from the flat store. Because every board's data is
 * already normalized in memory and cache-hydrated, switching boards only swaps
 * `activeBoardId` — this component recomputes from O(1) maps with no fetch and
 * no loading state.
 *
 * The grouping is derived in a `useMemo` over stable store slices rather than in
 * the zustand selector itself: a selector that allocated a fresh object on every
 * call would defeat `useSyncExternalStore`'s snapshot caching and loop.
 */
export function BoardView() {
  const board = useWorkspaceStore(selectActiveBoard);
  const issuesById = useWorkspaceStore((s) => s.issuesById);
  const boardIssueIds = useWorkspaceStore((s) => s.boardIssueIds);

  const columns = useMemo(
    () => (board ? boardColumns(issuesById, boardIssueIds, board.id) : {}),
    [board, issuesById, boardIssueIds],
  );

  if (!board) return <p style={{ padding: 16 }}>Select a board.</p>;

  return (
    <div style={{ display: "flex", gap: 12, padding: 16, overflowX: "auto" }}>
      {board.columns.map((column) => {
        const issues = columns[column.id] ?? [];
        return (
          <section
            key={column.id}
            style={{
              minWidth: 220,
              background: "#f4f4f5",
              borderRadius: 8,
              padding: 8,
            }}
          >
            <header style={{ fontWeight: 600, fontSize: 13, marginBottom: 8 }}>
              {column.name} <span style={{ color: "#71717a" }}>({issues.length})</span>
            </header>
            {issues.slice(0, 50).map((issue) => (
              <div
                key={issue.id}
                style={{
                  background: "#fff",
                  border: "1px solid #e4e4e7",
                  borderRadius: 6,
                  padding: 8,
                  marginBottom: 6,
                  fontSize: 12,
                }}
              >
                <strong>{issue.key}</strong> {issue.title}
              </div>
            ))}
            {issues.length > 50 && (
              <p style={{ fontSize: 11, color: "#71717a" }}>
                +{issues.length - 50} more
              </p>
            )}
          </section>
        );
      })}
    </div>
  );
}
