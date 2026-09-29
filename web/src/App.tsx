import { useCallback, useEffect, useRef, useState } from "react";
import { useTranslation } from "react-i18next";
import { useWorkspaceStore } from "./store/workspaceStore";
import {
  useWorkspaceSync,
  type WorkspaceSync,
  type WorkspaceSyncOptions,
} from "./api";
import type { ID } from "./store/types";
import { BoardSwitcher } from "./components/BoardSwitcher";
import { BoardView } from "./components/BoardView";
import { IssueDetail } from "./components/IssueDetail";
import { PreferenceControls } from "./components/PreferenceControls";
import { BoardIcon, InboxIcon, LogoMark, TimelineIcon } from "./components/icons";
import {
  EmptyState,
  ErrorState,
  LoadingBoard,
  StaleBanner,
  SyncIndicator,
} from "./components/states";
import { ProfilingHarness } from "./profiling/ProfilingHarness";

type Tab = "boards" | "timeline";

export interface AppProps {
  /**
   * Injected by the suite so the app can be driven over a recorded transport.
   * Production renders `<App />` and the client is built from the environment.
   */
  sync?: WorkspaceSyncOptions;
}

/** The card for an item, if the board currently renders one. */
function findCard(issueId: ID): HTMLElement | null {
  for (const card of document.querySelectorAll<HTMLElement>("[data-issue-id]")) {
    if (card.dataset.issueId === issueId) return card;
  }
  return null;
}

function BoardsTab({ sync }: { sync: WorkspaceSync }) {
  const { t } = useTranslation();
  const activeBoardId = useWorkspaceStore((s) => s.activeBoardId);
  const hasBoards = useWorkspaceStore((s) => Object.keys(s.boardsById).length > 0);
  const [openIssueId, setOpenIssueId] = useState<ID | null>(null);
  const openIssueIdRef = useRef<ID | null>(null);
  // What had focus when the panel opened, for when the card it came from is
  // no longer on screen by the time it closes.
  const openerRef = useRef<HTMLElement | null>(null);
  const returnFocusRef = useRef<HTMLElement | null>(null);

  const showIssue = useCallback((issueId: ID | null) => {
    openIssueIdRef.current = issueId;
    setOpenIssueId(issueId);
  }, []);

  const openIssue = useCallback(
    (issueId: ID) => {
      openerRef.current =
        document.activeElement instanceof HTMLElement ? document.activeElement : null;
      showIssue(issueId);
    },
    [showIssue],
  );

  const closeIssue = useCallback(() => {
    const current = openIssueIdRef.current;
    // Back to the card of the item last shown — after following a link, that
    // is a different card from the one that opened the panel.
    returnFocusRef.current = (current !== null ? findCard(current) : null) ?? openerRef.current;
    showIssue(null);
  }, [showIssue]);

  // After the commit that unmounts the panel, or its focus handling would win.
  useEffect(() => {
    if (openIssueId !== null) return;
    const target = returnFocusRef.current;
    returnFocusRef.current = null;
    if (target?.isConnected) target.focus();
  }, [openIssueId]);

  // Nothing cached and nothing fetched yet. Rendering the board here would show
  // an empty board indistinguishable from a workspace that really has none.
  if (sync.status === "loading") return <LoadingBoard />;
  if (sync.status === "error") return <ErrorState sync={sync} />;

  if (!hasBoards) {
    return (
      <EmptyState
        icon={<InboxIcon size={22} />}
        title={t("board.noBoards.title")}
        body={t("board.noBoards.body")}
      />
    );
  }

  return (
    <>
      {sync.status === "stale" && <StaleBanner sync={sync} />}
      <BoardSwitcher />
      {/* Keyed on the board, never on the locale or the sync status: re-keying
          on either would re-mount the board and lose the scroll position on a
          language swap or a background revalidation. */}
      <BoardView key={activeBoardId} onOpenIssue={openIssue} />
      {openIssueId && (
        <IssueDetail issueId={openIssueId} onClose={closeIssue} onNavigate={showIssue} />
      )}
    </>
  );
}

export function App({ sync: syncOptions }: AppProps = {}) {
  const { t } = useTranslation();
  const sync = useWorkspaceSync(syncOptions);
  const workspace = useWorkspaceStore((s) => s.workspace);
  const [tab, setTab] = useState<Tab>("boards");

  useEffect(() => {
    const product = t("app.title");
    document.title = workspace ? `${workspace.name} · ${product}` : product;
  }, [workspace, t]);

  return (
    <div className="vec-app">
      <a className="vec-skip" href="#vec-main">
        {t("app.skipToContent")}
      </a>
      <header className="vec-topbar">
        <div className="vec-topbar__identity">
          <span className="vec-brand">
            <LogoMark />
            <span className="vec-brand__name">{t("app.title")}</span>
          </span>
          {workspace && (
            <>
              <span className="vec-topbar__divider" aria-hidden="true" />
              <span className="vec-workspace">
                {/* Workspace names are user data, not chrome — deliberately not localized. */}
                <span className="vec-workspace__name">{workspace.name}</span>
                <span className="vec-workspace__key">{workspace.key}</span>
              </span>
            </>
          )}
        </div>

        <nav className="vec-nav" aria-label={t("nav.label")}>
          <button
            type="button"
            className="vec-nav__item"
            aria-current={tab === "boards" ? "page" : undefined}
            onClick={() => setTab("boards")}
          >
            <BoardIcon />
            {t("nav.boards")}
          </button>
          <button
            type="button"
            className="vec-nav__item"
            aria-current={tab === "timeline" ? "page" : undefined}
            onClick={() => setTab("timeline")}
          >
            <TimelineIcon />
            {t("nav.timeline")}
          </button>
        </nav>

        <div className="vec-topbar__end">
          <SyncIndicator status={sync.status} />
          <PreferenceControls />
        </div>
      </header>

      <main id="vec-main" className="vec-main" tabIndex={-1}>
        {/* The profiler generates its own data and is deliberately not gated on
            the sync state: it must run with no server listening. */}
        {tab === "boards" ? <BoardsTab sync={sync} /> : <ProfilingHarness />}
      </main>
    </div>
  );
}
