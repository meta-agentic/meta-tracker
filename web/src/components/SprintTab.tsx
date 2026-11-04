import { useCallback, useMemo, useState } from "react";
import { useTranslation } from "react-i18next";
import { useSprints, type WireSprint, type WorkspaceApiClient } from "../api";
import { useWorkspaceStore } from "../store/workspaceStore";
import { selectActiveBoard } from "../store/selectors";
import { uiTracking } from "../store/cache";
import { useDateFormat, useNumberFormat } from "../i18n/format";
import type { ID } from "../store/types";
import { BoardView } from "./BoardView";
import { ErrorBoundary } from "./ErrorBoundary";
import { BoardIcon, InboxIcon, RetryIcon } from "./icons";
import { BoardCrash, EmptyState, LoadingBoard } from "./states";

/** The sprint last opened, so a reload lands on it. */
const SPRINT_KEY = "ui:sprint";

/** `2026-10-15` as a local date: `new Date(string)` would read it as UTC and can show the day before. */
function parseDate(iso: string): Date | null {
  const match = /^(\d{4})-(\d{2})-(\d{2})/.exec(iso);
  return match ? new Date(Number(match[1]), Number(match[2]) - 1, Number(match[3])) : null;
}

function SprintHeader({ sprint }: { sprint: WireSprint }) {
  const { t } = useTranslation();
  const number = useNumberFormat();
  const date = useDateFormat({ dateStyle: "medium" });
  const show = (iso: string | null) => {
    const parsed = iso === null ? null : parseDate(iso);
    return parsed === null ? null : date(parsed);
  };
  const start = show(sprint.start);
  const end = show(sprint.end);
  // A sprint without dates is shown as such; nothing is made up in their place.
  const dates =
    start && end
      ? t("sprint.dates", { start: start, end: end })
      : start
        ? t("sprint.startOnly", { start })
        : t("sprint.noDates");

  return (
    <section className="vec-sprint-header" aria-labelledby="vec-sprint-name">
      <div className="vec-sprint-header__title">
        <h2 id="vec-sprint-name" className="vec-sprint-header__name">
          {/* Sprint names are user data, not chrome — deliberately not localized. */}
          {sprint.name}
        </h2>
        <span className="vec-sprint-state" data-state={sprint.state}>
          {t(`sprint.state.${sprint.state}`, { defaultValue: sprint.state })}
        </span>
        <span className="vec-muted">{dates}</span>
      </div>
      <dl className="vec-sprint-header__facts">
        <div>
          <dt>{t("sprint.goal")}</dt>
          <dd>{sprint.goal ?? <span className="vec-muted">{t("sprint.noGoal")}</span>}</dd>
        </div>
        <div>
          <dt>{t("sprint.committed")}</dt>
          <dd>{number(sprint.committedPoints)}</dd>
        </div>
        <div>
          <dt>{t("sprint.done")}</dt>
          <dd>{number(sprint.donePoints)}</dd>
        </div>
      </dl>
    </section>
  );
}

function SprintBoard({
  keys,
  boardId,
  onOpenIssue,
}: {
  keys: ReadonlySet<string> | null;
  boardId: ID | null;
  onOpenIssue: (issueId: ID) => void;
}) {
  const { t } = useTranslation();
  const number = useNumberFormat();
  const itemsById = useWorkspaceStore((s) => s.issuesById);
  const board = useWorkspaceStore(selectActiveBoard);
  const { only, offBoard, elsewhere } = useMemo(() => {
    const shown = new Set(board?.columns.map((column) => column.id));
    const ids = new Set<ID>();
    let off = 0;
    let other = 0;
    for (const key of keys ?? []) {
      const issue = itemsById[key];
      if (!issue) continue;
      if (issue.boardId !== boardId) other += 1;
      else {
        ids.add(issue.id);
        if (!shown.has(issue.columnId)) off += 1;
      }
    }
    return { only: ids, offBoard: off, elsewhere: other };
  }, [keys, itemsById, boardId, board]);

  if (keys !== null && only.size === 0) {
    // Items on a board that is not open are not "no items": say where they are.
    return elsewhere > 0 ? (
      <EmptyState
        icon={<BoardIcon size={22} />}
        title={t("sprint.otherBoard.title")}
        body={t("sprint.otherBoard.body")}
      />
    ) : (
      <EmptyState
        icon={<BoardIcon size={22} />}
        title={t("sprint.noItems.title")}
        body={t("sprint.noItems.body")}
      />
    );
  }
  return (
    <>
      {offBoard > 0 && (
        <p className="vec-muted-note vec-sprint-note">
          {t("sprint.offBoard", { count: offBoard, items: number(offBoard) })}
        </p>
      )}
      <ErrorBoundary resetKey={boardId} fallback={(error) => <BoardCrash error={error} />}>
        <BoardView onOpenIssue={onOpenIssue} only={only} />
      </ErrorBoundary>
    </>
  );
}

export interface SprintTabProps {
  createClient?: () => WorkspaceApiClient;
  onOpenIssue: (issueId: ID) => void;
}

/**
 * The sprint as a board: the Boards tab's columns and cards, narrowed to the
 * items of one sprint, under a header with its dates, goal and points. Reading
 * only — the items are imported, and nothing here moves or edits one.
 *
 * Which items are in the sprint is the server's answer; what each one looks like
 * is the store's, which already holds every item. An item sits in the column it
 * is in now, so a closed sprint shows its items where they ended up.
 */
export function SprintTab({ createClient, onOpenIssue }: SprintTabProps) {
  const { t } = useTranslation();
  const activeBoardId = useWorkspaceStore((s) => s.activeBoardId);
  const [chosen, setChosen] = useState<string | null>(() => {
    const stored = uiTracking.read<unknown>(SPRINT_KEY, null);
    return typeof stored === "string" ? stored : null;
  });

  const shown = useSprints(chosen, createClient);
  const { selected } = shown;
  const choose = useCallback((key: string) => {
    setChosen(key);
    uiTracking.write(SPRINT_KEY, key);
  }, []);
  const latestClosed = useMemo(() => shown.sprints.find((s) => s.state === "COMPLETED") ?? null, [shown.sprints]);

  if (shown.status === "loading") return <LoadingBoard />;
  const failure = (title: string) => (
    <div className="vec-state" role="alert">
      <h2 className="vec-state__title">{title}</h2>
      {shown.error && <code className="vec-state__diagnostic">{shown.error.message}</code>}
      <button type="button" className="vec-button vec-button--primary" onClick={shown.retry}>
        <RetryIcon size={14} />
        {t("app.retry")}
      </button>
    </div>
  );
  // Without the list there is no switcher to keep; a failed sprint keeps it.
  if (shown.status === "error") return failure(t("sprint.error"));
  if (shown.sprints.length === 0) {
    return (
      <EmptyState
        icon={<InboxIcon size={22} />}
        title={t("sprint.none.title")}
        body={t("sprint.none.body")}
      />
    );
  }

  return (
    <>
      <div className="vec-boardbar">
        <nav className="vec-boardbar__boards" aria-label={t("sprint.switcher")}>
          {shown.sprints.map((sprint) => (
            <button
              key={sprint.key}
              type="button"
              className="vec-board-tab"
              aria-current={sprint.key === selected?.key ? "true" : undefined}
              onClick={() => choose(sprint.key)}
            >
              {sprint.name}
            </button>
          ))}
        </nav>
      </div>
      <p className="vec-muted-note vec-sprint-note">{t("sprint.readOnly")}</p>
      {selected ? (
        <>
          <SprintHeader sprint={selected} />
          {shown.itemsStatus === "error" ? (
            failure(t("sprint.itemsError"))
          ) : shown.itemsStatus === "loading" ? (
            <LoadingBoard />
          ) : (
            <SprintBoard
              key={`${selected.key}:${activeBoardId}`}
              keys={shown.itemKeys}
              boardId={activeBoardId}
              onOpenIssue={onOpenIssue}
            />
          )}
        </>
      ) : (
        <EmptyState
          icon={<BoardIcon size={22} />}
          title={t("sprint.noActive.title")}
          body={t("sprint.noActive.body")}
        />
      )}
      {!selected && latestClosed && (
        <div className="vec-sprint-offer">
          <button type="button" className="vec-button" onClick={() => choose(latestClosed.key)}>
            {t("sprint.noActive.show", { name: latestClosed.name })}
          </button>
        </div>
      )}
    </>
  );
}
