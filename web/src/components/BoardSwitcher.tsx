import { useShallow } from "zustand/react/shallow";
import { useWorkspaceStore } from "../store/workspaceStore";
import { selectBoardList } from "../store/selectors";

export function BoardSwitcher() {
  // useShallow is safe here: the sorted array is fresh each call but its
  // elements are the same stable board refs, so a shallow compare bails out.
  const boards = useWorkspaceStore(useShallow(selectBoardList));
  const activeBoardId = useWorkspaceStore((s) => s.activeBoardId);
  const setActiveBoard = useWorkspaceStore((s) => s.setActiveBoard);

  return (
    <nav style={{ display: "flex", gap: 6, padding: "8px 16px", flexWrap: "wrap" }}>
      {boards.map((board) => (
        <button
          key={board.id}
          onClick={() => setActiveBoard(board.id)}
          style={{
            padding: "4px 10px",
            borderRadius: 6,
            border: "1px solid var(--vec-border-strong)",
            background: board.id === activeBoardId
              ? "var(--vec-accent)"
              : "var(--vec-surface)",
            color: board.id === activeBoardId
              ? "var(--vec-on-accent)"
              : "var(--vec-text)",
            cursor: "pointer",
            fontSize: 12,
          }}
        >
          {/* Board names are user data, not chrome — deliberately not localized. */}
          {board.name}
        </button>
      ))}
    </nav>
  );
}
