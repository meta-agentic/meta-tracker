import { useEffect, useState } from "react";
import { useTranslation } from "react-i18next";
import { useWorkspaceStore, workspaceStore } from "./store/workspaceStore";
import { generateWorkspace } from "./lib/synthetic";
import { BoardSwitcher } from "./components/BoardSwitcher";
import { BoardView } from "./components/BoardView";
import { PreferenceControls } from "./components/PreferenceControls";
import { ProfilingHarness } from "./profiling/ProfilingHarness";

type Tab = "boards" | "timeline";

export function App() {
  const { t } = useTranslation();
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

  if (!hydrated) return <p style={{ padding: 16 }}>{t("app.loading")}</p>;

  return (
    <div style={{ color: "var(--vec-text)" }}>
      <header
        style={{
          display: "flex",
          gap: 8,
          padding: "10px 16px",
          borderBottom: "1px solid var(--vec-border)",
          alignItems: "center",
        }}
      >
        <strong style={{ marginRight: 12 }}>{t("app.title")}</strong>
        <button onClick={() => setTab("boards")} disabled={tab === "boards"}>
          {t("nav.boards")}
        </button>
        <button onClick={() => setTab("timeline")} disabled={tab === "timeline"}>
          {t("nav.timeline")}
        </button>
        <span style={{ marginLeft: "auto", display: "flex", gap: 12 }}>
          <PreferenceControls />
        </span>
      </header>

      {tab === "boards" ? (
        <>
          <BoardSwitcher />
          {/* Keyed on the board, never on the locale: re-keying on locale would
              re-mount the board and lose the scroll position on every swap. */}
          <BoardView key={activeBoardId} />
        </>
      ) : (
        <ProfilingHarness />
      )}
    </div>
  );
}
