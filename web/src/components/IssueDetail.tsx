import { useEffect, useMemo, useRef, type RefObject } from "react";
import { createPortal } from "react-dom";
import { useTranslation } from "react-i18next";
import { useWorkspaceStore } from "../store/workspaceStore";
import { useDateFormat, useNumberFormat } from "../i18n/format";
import type { ID } from "../store/types";
import { EpicChip, TypeBadge } from "./IssueCard";
import { CloseIcon, LinkIcon } from "./icons";
import { Markdown } from "./Markdown";
import { ancestorChain, epicAsIssue, linkGroups, type ItemIndex, type ItemNode, type LinkRow } from "../lib/itemTree";

export interface IssueDetailProps {
  issueId: ID;
  onClose: () => void;
  /** Opens a linked item in place of this one. */
  onNavigate: (issueId: ID) => void;
}

const FOCUSABLE =
  'button:not([disabled]), [href], input:not([disabled]), select:not([disabled]), textarea:not([disabled]), [tabindex]:not([tabindex="-1"])';

/** `YYYY-MM-DD` read as a local calendar date, so no timezone shifts the day. */
function parseDay(value: string): Date | null {
  const match = /^(\d{4})-(\d{2})-(\d{2})$/.exec(value);
  if (!match) return null;
  const date = new Date(Number(match[1]), Number(match[2]) - 1, Number(match[3]));
  return Number.isNaN(date.getTime()) ? null : date;
}

/**
 * Makes the sheet modal for as long as it is mounted.
 *
 * Everything else on the page is made `inert` — not focusable, not clickable,
 * hidden from assistive technology — so focus cannot leave the sheet by a click
 * or a Tab. Escape and Tab are handled at the document, not on the sheet, so
 * they still work when focus sits somewhere a keydown on the sheet would never
 * see. Only the `inert` attributes this added are removed again.
 */
function useModal(
  rootRef: RefObject<HTMLElement | null>,
  panelRef: RefObject<HTMLElement | null>,
  onClose: () => void,
) {
  const onCloseRef = useRef(onClose);
  useEffect(() => {
    onCloseRef.current = onClose;
  });

  useEffect(() => {
    const root = rootRef.current;
    // Nothing rendered (the item vanished and the panel is about to close).
    if (!root) return;
    const made: Element[] = [];
    for (const element of Array.from(document.body.children)) {
      if (element !== root && !element.hasAttribute("inert")) {
        element.setAttribute("inert", "");
        made.push(element);
      }
    }

    const onKeyDown = (event: KeyboardEvent) => {
      const panel = panelRef.current;
      if (!panel) return;
      if (event.key === "Escape") {
        event.preventDefault();
        onCloseRef.current();
        return;
      }
      if (event.key !== "Tab") return;
      const focusable = Array.from(panel.querySelectorAll<HTMLElement>(FOCUSABLE));
      if (focusable.length === 0) return;
      const first = focusable[0];
      const last = focusable[focusable.length - 1];
      const inside = panel.contains(document.activeElement);
      // The panel itself takes focus when its text is clicked. It sits before
      // the first control, so Shift+Tab from there leaves the sheet unless it
      // is caught here too.
      const atStart = document.activeElement === first || document.activeElement === panel;
      if (!inside || (event.shiftKey && atStart)) {
        event.preventDefault();
        (event.shiftKey ? last : first).focus();
      } else if (!event.shiftKey && document.activeElement === last) {
        event.preventDefault();
        first.focus();
      }
    };
    document.addEventListener("keydown", onKeyDown);

    return () => {
      document.removeEventListener("keydown", onKeyDown);
      for (const element of made) element.removeAttribute("inert");
    };
  }, [rootRef, panelRef]);
}

/**
 * The item detail panel: a modal side sheet over the board.
 *
 * Portalled to `<body>` so the rest of the page can be made inert around it
 * (see `useModal`). The caller owns returning focus to the card that opened it,
 * because only the caller knows which card that was. The board underneath is
 * not unmounted, so closing the panel returns to the same scroll position.
 */
export function IssueDetail({ issueId, onClose, onNavigate }: IssueDetailProps) {
  const { t } = useTranslation();
  const number = useNumberFormat();
  const day = useDateFormat({ dateStyle: "medium" });
  const storedIssue = useWorkspaceStore((s) =>
    Object.hasOwn(s.issuesById, issueId) ? s.issuesById[issueId] : null,
  );
  const storedEpic = useWorkspaceStore((s) =>
    Object.hasOwn(s.epicsById, issueId) ? s.epicsById[issueId] : null,
  );
  // An epic opens in the same panel, read as an issue.
  const issue = useMemo(
    () => storedIssue ?? (storedEpic ? epicAsIssue(storedEpic) : null),
    [storedIssue, storedEpic],
  );
  const epic = useWorkspaceStore((s) =>
    issue?.epicId ? (s.epicsById[issue.epicId] ?? null) : null,
  );
  const board = useWorkspaceStore((s) => (issue ? (s.boardsById[issue.boardId] ?? null) : null));
  const issuesById = useWorkspaceStore((s) => s.issuesById);
  const epicsById = useWorkspaceStore((s) => s.epicsById);
  const boardsById = useWorkspaceStore((s) => s.boardsById);
  const rootRef = useRef<HTMLDivElement>(null);
  const panelRef = useRef<HTMLElement>(null);
  const closeRef = useRef<HTMLButtonElement>(null);

  const index = useMemo<ItemIndex>(
    () => ({ issuesById, epicsById, boardsById }),
    [issuesById, epicsById, boardsById],
  );
  const ancestors = useMemo(() => ancestorChain(index, issueId), [index, issueId]);
  const groups = useMemo(() => linkGroups(index, issueId), [index, issueId]);

  // A revalidation can remove the item under an open panel. Closing beats
  // rendering a panel about nothing.
  useEffect(() => {
    if (!issue) onClose();
  }, [issue, onClose]);

  useModal(rootRef, panelRef, onClose);

  useEffect(() => {
    closeRef.current?.focus();
  }, [issueId]);

  useEffect(() => {
    const { overflow } = document.body.style;
    document.body.style.overflow = "hidden";
    return () => {
      document.body.style.overflow = overflow;
    };
  }, []);

  if (!issue) return null;

  const status = board?.columns.find((column) => column.id === issue.columnId)?.name ?? null;
  const start = issue.startDate ? parseDay(issue.startDate) : null;
  const due = issue.dueDate ? parseDay(issue.dueDate) : null;
  const titleId = `vec-detail-title-${issue.id}`;

  return createPortal(
    <div className="vec-sheet-root" ref={rootRef}>
      <div className="vec-scrim" aria-hidden="true" onClick={onClose} />
      <aside
        ref={panelRef}
        className="vec-sheet"
        role="dialog"
        aria-modal="true"
        aria-labelledby={titleId}
        // Focusable itself, so a click on the sheet's text lands focus here
        // rather than on something outside it.
        tabIndex={-1}
      >
        <div className="vec-sheet__head">
          <TypeBadge type={issue.type} />
          <span className="vec-key">{issue.key}</span>
          <button
            ref={closeRef}
            type="button"
            className="vec-icon-button vec-sheet__close"
            aria-label={t("detail.close")}
            onClick={onClose}
          >
            <CloseIcon />
          </button>
        </div>

        <div className="vec-sheet__body">
          {ancestors.length > 0 && (
            <nav className="vec-ancestors" aria-label={t("detail.ancestors")}>
              {ancestors.map((ancestor, depth) => (
                <AncestorHeader
                  key={ancestor.id}
                  node={ancestor}
                  depth={depth}
                  onOpen={onNavigate}
                />
              ))}
            </nav>
          )}
          <h2 id={titleId} className="vec-sheet__title">
            {issue.title}
          </h2>

          <dl className="vec-props">
            <div>
              <dt>{t("detail.status")}</dt>
              <dd>{status ? <span className="vec-status">{status}</span> : "—"}</dd>
            </div>
            <div>
              <dt>{t("detail.epic")}</dt>
              <dd>{epic ? <EpicChip epic={epic} /> : t("detail.none")}</dd>
            </div>
            <div>
              <dt>{t("detail.points")}</dt>
              <dd>
                {issue.storyPoints !== null && (
                  <span className="vec-points">{number(issue.storyPoints)}</span>
                )}
                {issue.extension != null && issue.intension != null && (
                  <span className="vec-muted vec-axes">
                    {t("detail.axes", {
                      extension: signed(issue.extension),
                      intension: signed(issue.intension),
                    })}
                  </span>
                )}
                {issue.storyPoints === null && (issue.extension == null || issue.intension == null) && "—"}
              </dd>
            </div>
            {issue.sprint && (
              <div>
                <dt>{t("detail.sprint")}</dt>
                <dd>
                  <span className="vec-chip vec-sprint-chip">{issue.sprint}</span>
                </dd>
              </div>
            )}
            {issue.priority && (
              <div>
                <dt>{t("detail.priority")}</dt>
                <dd>{issue.priority}</dd>
              </div>
            )}
            {start && (
              <div>
                <dt>{t("detail.start")}</dt>
                <dd>{day(start)}</dd>
              </div>
            )}
            {due && (
              <div>
                <dt>{t("detail.due")}</dt>
                <dd>{day(due)}</dd>
              </div>
            )}
            <div>
              <dt>{t("detail.labels")}</dt>
              <dd>
                {issue.labels.length > 0 ? (
                  <span className="vec-chip-row">
                    {issue.labels.map((label) => (
                      <span key={label} className="vec-chip">
                        {label}
                      </span>
                    ))}
                  </span>
                ) : (
                  t("detail.none")
                )}
              </dd>
            </div>
          </dl>

          <section className="vec-sheet__section" aria-labelledby={`${titleId}-description`}>
            <h3 id={`${titleId}-description`}>{t("detail.description")}</h3>
            {issue.description ? (
              <Markdown source={issue.description} />
            ) : (
              <p className="vec-muted-note">{t("detail.noDescription")}</p>
            )}
          </section>

          <section className="vec-sheet__section" aria-labelledby={`${titleId}-links`}>
            <h3 id={`${titleId}-links`}>{t("detail.links")}</h3>
            {groups.length === 0 && <p className="vec-muted-note">{t("detail.noLinks")}</p>}
            {groups.map((group) => (
              <div key={group.group} className="vec-links">
                <h4>{t(`detail.${group.group}`)}</h4>
                <ul>
                  {group.rows.map((row) => (
                    <li key={row.key}>
                      <LinkRowView row={row} onOpen={onNavigate} />
                    </li>
                  ))}
                </ul>
              </div>
            ))}
          </section>
        </div>
      </aside>
    </div>,
    document.body,
  );
}

/** `+0.2`, `-0.3`, `0`: the sign is part of an axis. */
function signed(value: number): string {
  return value > 0 ? `+${value}` : String(value);
}

/** One ancestor, indented one step per level, with the facts the dashboard's header shows. */
function AncestorHeader({
  node,
  depth,
  onOpen,
}: {
  node: ItemNode;
  depth: number;
  onOpen: (id: ID) => void;
}) {
  const { t } = useTranslation();
  return (
    <div className={`vec-ancestor vec-ancestor--depth-${Math.min(depth, 8)}`}>
      <button type="button" className="vec-key vec-ancestor__key" onClick={() => onOpen(node.id)}>
        {node.key}
      </button>
      <span className="vec-ancestor__title">{node.title}</span>
      {node.status && <span className="vec-status">{node.status}</span>}
      <span className="vec-chip">{node.type ?? t("detail.none")}</span>
      {node.priority && <span className="vec-chip">{node.priority}</span>}
      {node.points !== null && <span className="vec-points">{node.points}</span>}
    </div>
  );
}

function LinkRowView({ row, onOpen }: { row: LinkRow; onOpen: (id: ID) => void }) {
  const { t } = useTranslation();
  const { node } = row;
  if (!node) {
    // A key the client never loaded: shown, not linked, so nothing on the
    // panel leads nowhere.
    return (
      <span className="vec-link-row vec-link-row--static">
        <LinkIcon size={14} />
        <span className="vec-key">{row.key}</span>
        <span className="vec-link-row__title vec-muted">{t("detail.notLoaded")}</span>
      </span>
    );
  }
  return (
    <button type="button" className="vec-link-row" onClick={() => onOpen(node.id)}>
      <LinkIcon size={14} />
      <span className="vec-key">{node.key}</span>
      <span className="vec-link-row__title">{node.title}</span>
      {node.status && <span className="vec-status">{node.status}</span>}
    </button>
  );
}
