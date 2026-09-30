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
import { BacklogTree, type BacklogTreeHandle } from "./components/BacklogTree";
import { IssueDetail } from "./components/IssueDetail";
import { PreferenceControls } from "./components/PreferenceControls";
import { BoardIcon, InboxIcon, LogoMark, TimelineIcon, TreeIcon } from "./components/icons";
import { ErrorBoundary } from "./components/ErrorBoundary";
import {
  BoardCrash,
  EmptyState,
  ErrorState,
  LoadingBoard,
  LoadingTree,
  StaleBanner,
  SyncIndicator,
} from "./components/states";
import { ProfilingHarness } from "./profiling/ProfilingHarness";

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
          language swap or a background revalidation. Inside its own boundary,
          so a board that fails to render leaves the shell and the switcher
          above it working; switching boards clears it. */}
      <ErrorBoundary resetKey={activeBoardId} fallback={(error) => <BoardCrash error={error} />}>
        <BoardView key={activeBoardId} onOpenIssue={openIssue} />
      </ErrorBoundary>
      {openIssueId && (
        <IssueDetail issueId={openIssueId} onClose={closeIssue} onNavigate={showIssue} />
      )}
    </>
  );
}

function BacklogTab({ sync }: { sync: WorkspaceSync }) {
  const { t } = useTranslation();
  const activeBoardId = useWorkspaceStore((s) => s.activeBoardId);
  const hasBoards = useWorkspaceStore((s) => Object.keys(s.boardsById).length > 0);
  const boardIssueIds = useWorkspaceStore((s) => s.boardIssueIds);
  const issuesById = useWorkspaceStore((s) => s.issuesById);
  const epicsById = useWorkspaceStore((s) => s.epicsById);
  const treeExpanded = useWorkspaceStore((s) => s.treeExpanded);
  const setTreeExpanded = useWorkspaceStore((s) => s.setTreeExpanded);
  const treeRef = useRef<BacklogTreeHandle>(null);
  const [openIssueId, setOpenIssueId] = useState<ID | null>(null);
  const openIssueIdRef = useRef<ID | null>(null);
  const returnToRef = useRef<ID | null>(null);

  const showIssue = useCallback((issueId: ID | null) => {
    openIssueIdRef.current = issueId;
    setOpenIssueId(issueId);
  }, []);

  const closeIssue = useCallback(() => {
    // Back to the row of the item last shown — after following a link, that is
    // a different row from the one that opened the sheet, if the tree shows it.
    returnToRef.current = openIssueIdRef.current;
    showIssue(null);
  }, [showIssue]);

  // After the commit that unmounts the sheet, or its focus handling would win.
  useEffect(() => {
    if (openIssueId !== null) return;
    const target = returnToRef.current;
    returnToRef.current = null;
    if (target !== null) treeRef.current?.focusRow(target);
  }, [openIssueId]);

  // Same gating as the board, for the same reason: an empty tree while loading
  // would read as a workspace with no backlog.
  if (sync.status === "loading") return <LoadingTree />;
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

  // The epics with a story on this board — every row here that can expand.
  // Both buttons touch only these, so another board's tree keeps its state.
  const boardEpics = () => {
    const ids = new Set<string>();
    for (const id of activeBoardId ? (boardIssueIds[activeBoardId] ?? []) : []) {
      const epicId = issuesById[id]?.epicId;
      if (epicId && epicsById[epicId]) ids.add(epicId);
    }
    return ids;
  };
  const expandAll = () => setTreeExpanded([...treeExpanded, ...boardEpics()]);
  const collapseAll = () => {
    const here = boardEpics();
    setTreeExpanded(treeExpanded.filter((id) => !here.has(id)));
  };

  return (
    <>
      {sync.status === "stale" && <StaleBanner sync={sync} />}
      <BoardSwitcher />
      {activeBoardId ? (
        <div className="vec-backlog">
          <div className="vec-backlog__toolbar">
            <button type="button" className="vec-button" onClick={expandAll}>
              {t("backlog.expandAll")}
            </button>
            <button type="button" className="vec-button" onClick={collapseAll}>
              {t("backlog.collapseAll")}
            </button>
          </div>
          {/* Keyed on the board for the same reason as the board view: the
              active row and the scroll position belong to one board. */}
          <BacklogTree
            key={activeBoardId}
            ref={treeRef}
            boardId={activeBoardId}
            onOpenIssue={showIssue}
          />
        </div>
      ) : (
        <p className="vec-muted-note">{t("board.empty")}</p>
      )}
      {openIssueId && (
        <IssueDetail issueId={openIssueId} onClose={closeIssue} onNavigate={showIssue} />
      )}
    </>
  );
}

export function App({ sync: syncOptions }: AppProps = {}) {
  const { t, i18n } = useTranslation();
  const sync = useWorkspaceSync(syncOptions);
  const workspace = useWorkspaceStore((s) => s.workspace);
  const tab = useWorkspaceStore((s) => s.activeTab);
  const setTab = useWorkspaceStore((s) => s.setActiveTab);

  const navRef = useRef<HTMLElement>(null);
  // On a narrow window the nav scrolls sideways. The stylesheet fades its
  // right edge while there is more to scroll to; this marks when there is not.
  const markNavEnd = useCallback(() => {
    const nav = navRef.current;
    if (nav) nav.dataset.atEnd = String(nav.scrollLeft + nav.clientWidth >= nav.scrollWidth - 1);
  }, []);
  // Keep the open tab in view, including after a reload restores one at the
  // far end, and re-mark the edge when a language swap changes the labels'
  // width.
  const language = i18n.language;
  useEffect(() => {
    navRef.current
      ?.querySelector<HTMLElement>('[aria-current="page"]')
      ?.scrollIntoView?.({ block: "nearest", inline: "nearest" });
    markNavEnd();
  }, [tab, language, markNavEnd]);
  useEffect(() => {
    window.addEventListener("resize", markNavEnd);
    return () => window.removeEventListener("resize", markNavEnd);
  }, [markNavEnd]);

  useEffect(() => {
    const product = t("app.title");
    document.title = workspace ? `${workspace.name} · ${product}` : product;
  }, [workspace, t]);

  return (
    // The backlog fills the window and scrolls inside its tree; the other tabs
    // scroll the page.
    <div className={tab === "backlog" ? "vec-app vec-app--fill" : "vec-app"}>
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

        <nav
          ref={navRef}
          className="vec-nav"
          aria-label={t("nav.label")}
          onScroll={markNavEnd}
        >
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
            aria-current={tab === "backlog" ? "page" : undefined}
            onClick={() => setTab("backlog")}
          >
            <TreeIcon />
            {t("nav.backlog")}
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
        {tab === "boards" && <BoardsTab sync={sync} />}
        {tab === "backlog" && <BacklogTab sync={sync} />}
        {/* The profiler generates its own data and is deliberately not gated on
            the sync state: it must run with no server listening. */}
        {tab === "timeline" && <ProfilingHarness />}
      </main>
    </div>
  );
}
