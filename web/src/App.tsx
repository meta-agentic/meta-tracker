import { useEffect, useState } from "react";
import { useWorkspaceStore, workspaceStore } from "./store/workspaceStore";
import { generateWorkspace } from "./lib/synthetic";
import { BoardSwitcher } from "./components/BoardSwitcher";
import { BoardView } from "./components/BoardView";
import { ProfilingHarness } from "./profiling/ProfilingHarness";

type Tab = "boards" | "timeline";

export function App() {
  const hydrated = useWorkspaceStore((s) => s.hydrated);
  const boardCount = useWorkspaceStore((s) => Object.keys(s.boardsById).length);
  const activeBoardId = useWorkspaceStore((s) => s.activeBoardId);
  const [tab, setTab] = useState<Tab>("boards");

  useEffect(() => {
    void workspaceStore.getState().hydrate();
  }, []);

  useEffect(() => {
    if (hydrated && boardCount === 0) {
      const store = workspaceStore.getState();
      store.ingestSnapshot(
        generateWorkspace({ boards: 5, epics: 18, issues: 4_000, years: 3 }),
      );
      store.setActiveBoard("board-0");
    }
  }, [hydrated, boardCount]);

  if (!hydrated) return <p style={{ padding: 16 }}>Loading workspace…</p>;

  return (
    <div style={{ fontFamily: "system-ui, sans-serif", color: "#18181b" }}>
      <header
        style={{
          display: "flex",
          gap: 8,
          padding: "10px 16px",
          borderBottom: "1px solid #e4e4e7",
          alignItems: "center",
        }}
      >
        <strong style={{ marginRight: 12 }}>Vectis</strong>
        <button onClick={() => setTab("boards")} disabled={tab === "boards"}>
          Boards
        </button>
        <button onClick={() => setTab("timeline")} disabled={tab === "timeline"}>
          Timeline profiler
        </button>
      </header>

      {tab === "boards" ? (
        <>
          <BoardSwitcher />
          <BoardView key={activeBoardId} />
        </>
      ) : (
        <ProfilingHarness />
      )}
    </div>
  );
}
