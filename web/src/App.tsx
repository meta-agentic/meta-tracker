import { useState } from "react";
import { useTranslation } from "react-i18next";
import { useWorkspaceStore } from "./store/workspaceStore";
import {
  useWorkspaceSync,
  type WorkspaceSync,
  type WorkspaceSyncOptions,
} from "./api/useWorkspaceSync";
import { BoardSwitcher } from "./components/BoardSwitcher";
import { BoardView } from "./components/BoardView";
import { PreferenceControls } from "./components/PreferenceControls";
import { ProfilingHarness } from "./profiling/ProfilingHarness";

type Tab = "boards" | "timeline";

export interface AppProps {
  /**
   * Injected by the suite so the app can be driven over a recorded transport.
   * Production renders `<App />` and the client is built from the environment.
   */
  sync?: WorkspaceSyncOptions;
}

/** Non-blocking: the cached board stays on screen underneath it. */
function StaleNotice({ sync }: { sync: WorkspaceSync }) {
  const { t } = useTranslation();
  return (
    <p
      role="status"
      style={{
        margin: 0,
        padding: "6px 16px",
        fontSize: 12,
        color: "var(--vec-text-muted)",
        borderBottom: "1px solid var(--vec-border)",
      }}
    >
      {t("app.stale")}{" "}
      {sync.canRetry && (
        <button onClick={sync.retry} style={{ fontSize: 12, cursor: "pointer" }}>
          {t("app.retry")}
        </button>
      )}
    </p>
  );
}

/** The no-cache failure: there is nothing to show, so this is the whole tab. */
function ErrorPanel({ sync }: { sync: WorkspaceSync }) {
  const { t } = useTranslation();
  return (
    <div role="alert" style={{ padding: 16 }}>
      <p style={{ fontWeight: 600, marginTop: 0 }}>{t("app.error")}</p>
      {sync.error && (
        <p style={{ fontSize: 12, color: "var(--vec-text-muted)" }}>
          {/* Server and network messages are diagnostics, not chrome — shown as
              received rather than translated into a vaguer sentence. */}
          {sync.error.message}
        </p>
      )}
      {sync.canRetry && (
        <button onClick={sync.retry} style={{ cursor: "pointer" }}>
          {t("app.retry")}
        </button>
      )}
    </div>
  );
}

function BoardsTab({ sync }: { sync: WorkspaceSync }) {
  const { t } = useTranslation();
  const activeBoardId = useWorkspaceStore((s) => s.activeBoardId);

  // Nothing cached and nothing fetched yet. Rendering the board here would show
  // an empty board indistinguishable from a workspace that really has none.
  if (sync.status === "loading") return <p style={{ padding: 16 }}>{t("app.loading")}</p>;
  if (sync.status === "error") return <ErrorPanel sync={sync} />;

  return (
    <>
      {sync.status === "stale" && <StaleNotice sync={sync} />}
      <BoardSwitcher />
      {/* Keyed on the board, never on the locale or the sync status: re-keying
          on either would re-mount the board and lose the scroll position on a
          language swap or a background revalidation. */}
      <BoardView key={activeBoardId} />
    </>
  );
}

export function App({ sync: syncOptions }: AppProps = {}) {
  const { t } = useTranslation();
  const sync = useWorkspaceSync(syncOptions);
  const [tab, setTab] = useState<Tab>("boards");

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

      {/* The profiler generates its own data and is deliberately not gated on
          the sync state: it must run with no server listening. */}
      {tab === "boards" ? <BoardsTab sync={sync} /> : <ProfilingHarness />}
    </div>
  );
}
