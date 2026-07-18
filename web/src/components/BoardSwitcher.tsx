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
            border: "1px solid #d4d4d8",
            background: board.id === activeBoardId ? "#4f46e5" : "#fff",
            color: board.id === activeBoardId ? "#fff" : "#18181b",
            cursor: "pointer",
            fontSize: 12,
          }}
        >
          {board.name}
        </button>
      ))}
    </nav>
  );
}
