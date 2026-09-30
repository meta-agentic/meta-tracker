import type { ReactNode } from "react";
import { useTranslation } from "react-i18next";
import type { SyncStatus, WorkspaceSync } from "../api";
import { skeletonColumnCount } from "../store/boardShape";
import { CloudOffIcon, RetryIcon, WarningIcon } from "./icons";

/**
 * The board's non-data states: nothing yet, nothing at all, something stale,
 * and a workspace (or board, or column) that is genuinely empty. Each one says
 * what happened and, where there is one, what to do next.
 */

function RetryButton({ sync, variant }: { sync: WorkspaceSync; variant: "primary" | "quiet" }) {
  const { t } = useTranslation();
  return (
    <button
      type="button"
      className={variant === "primary" ? "vec-button vec-button--primary" : "vec-button"}
      onClick={sync.retry}
    >
      <RetryIcon size={14} />
      {t("app.retry")}
    </button>
  );
}

/** Placeholder cards per skeleton column, repeated for as many columns as there are. */
const SKELETON_CARDS = [3, 2, 4, 1, 2];

/**
 * Cold load with no cache. A board-shaped placeholder rather than a spinner, so
 * the layout does not jump when the data lands — but never an empty board,
 * which would be indistinguishable from a workspace that really has none.
 */
export function LoadingBoard() {
  const { t } = useTranslation();
  const columns = skeletonColumnCount();
  return (
    <div className="vec-loading" role="status" aria-live="polite">
      <p className="vec-loading__caption">
        <span className="vec-spinner" aria-hidden="true" />
        {t("app.loading")}
      </p>
      <div className="vec-board" aria-hidden="true">
        {Array.from({ length: columns }, (_, column) => (
          <div key={column} className="vec-column vec-column--skeleton">
            <div className="vec-skeleton vec-skeleton--heading" />
            {Array.from({ length: SKELETON_CARDS[column % SKELETON_CARDS.length] }, (_, card) => (
              <div key={card} className="vec-skeleton vec-skeleton--card" />
            ))}
          </div>
        ))}
      </div>
    </div>
  );
}

const SKELETON_TREE_ROWS = [0, 1, 1, 0, 1, 0];

/** The backlog tab's cold load: the same reasoning as `LoadingBoard`, in the tree's shape. */
export function LoadingTree() {
  const { t } = useTranslation();
  return (
    <div className="vec-loading" role="status" aria-live="polite">
      <p className="vec-loading__caption">
        <span className="vec-spinner" aria-hidden="true" />
        {t("app.loading")}
      </p>
      <div className="vec-tree vec-tree--skeleton" aria-hidden="true">
        {SKELETON_TREE_ROWS.map((depth, row) => (
          <div
            key={row}
            className="vec-skeleton vec-skeleton--row"
            style={{ marginLeft: 12 + depth * 24 }}
          />
        ))}
      </div>
    </div>
  );
}

/** The no-cache failure: there is nothing to show, so this is the whole tab. */
export function ErrorState({ sync }: { sync: WorkspaceSync }) {
  const { t } = useTranslation();
  return (
    <div className="vec-state" role="alert">
      <span className="vec-state__icon vec-state__icon--danger">
        <CloudOffIcon size={22} />
      </span>
      <h2 className="vec-state__title">{t("app.error")}</h2>
      <p className="vec-state__body">{t("app.errorHint")}</p>
      {sync.error && (
        // Server and network messages are diagnostics, not chrome — shown as
        // received rather than translated into a vaguer sentence.
        <code className="vec-state__diagnostic">{sync.error.message}</code>
      )}
      {sync.canRetry && <RetryButton sync={sync} variant="primary" />}
    </div>
  );
}

/** Non-blocking: the cached board stays on screen underneath it. */
export function StaleBanner({ sync }: { sync: WorkspaceSync }) {
  const { t } = useTranslation();
  return (
    <div className="vec-banner" role="status">
      <WarningIcon size={16} />
      <span className="vec-banner__text">{t("app.stale")}</span>
      {sync.canRetry && <RetryButton sync={sync} variant="quiet" />}
    </div>
  );
}

export function EmptyState({
  icon,
  title,
  body,
}: {
  icon: ReactNode;
  title: string;
  body: string;
}) {
  return (
    <div className="vec-state">
      <span className="vec-state__icon">{icon}</span>
      <h2 className="vec-state__title">{title}</h2>
      <p className="vec-state__body">{body}</p>
    </div>
  );
}

/**
 * The shell's quiet connection readout. Deliberately not a live region: the
 * stale banner and the error panel already announce the states that need
 * attention, and a second announcement of the same event is noise.
 */
export function SyncIndicator({ status }: { status: SyncStatus }) {
  const { t } = useTranslation();
  const label = t(`sync.${status}`);
  return (
    <span className="vec-sync" data-status={status} title={label}>
      <span className="vec-sync__dot" aria-hidden="true" />
      <span className="vec-sync__label">{label}</span>
    </span>
  );
}

/** What the top-level error boundary shows in place of a crashed tree. */
export function CrashFallback({ error }: { error: Error }) {
  const { t } = useTranslation();
  return (
    <div className="vec-state" role="alert">
      <span className="vec-state__icon vec-state__icon--danger">
        <WarningIcon size={22} />
      </span>
      <h2 className="vec-state__title">{t("crash.title")}</h2>
      <p className="vec-state__body">{t("crash.body")}</p>
      <code className="vec-state__diagnostic">{error.message}</code>
      <button
        type="button"
        className="vec-button vec-button--primary"
        onClick={() => window.location.reload()}
      >
        <RetryIcon size={14} />
        {t("crash.reload")}
      </button>
    </div>
  );
}

/**
 * What the board's own error boundary shows in place of a board that failed to
 * render. The shell and the board switcher above it keep working, so the way
 * out is right there; nothing here claims a reload would fix it, because a
 * render error from the data is usually the same on every render.
 */
export function BoardCrash({ error }: { error: Error }) {
  const { t } = useTranslation();
  return (
    <div className="vec-state" role="alert">
      <span className="vec-state__icon vec-state__icon--danger">
        <WarningIcon size={22} />
      </span>
      <h2 className="vec-state__title">{t("crash.board.title")}</h2>
      <p className="vec-state__body">{t("crash.board.body")}</p>
      <code className="vec-state__diagnostic">{error.message}</code>
    </div>
  );
}
