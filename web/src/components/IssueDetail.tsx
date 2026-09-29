import { useEffect, useMemo, useRef, type KeyboardEvent } from "react";
import { useTranslation } from "react-i18next";
import { useWorkspaceStore } from "../store/workspaceStore";
import { useDateFormat, useNumberFormat } from "../i18n/format";
import type { ID, Issue } from "../store/types";
import { EpicChip, TypeBadge } from "./IssueCard";
import { CloseIcon, LinkIcon } from "./icons";

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
 * The item detail panel: a modal side sheet over the board.
 *
 * Modal, so focus is held inside it while it is open and Escape closes it; the
 * caller owns returning focus to the card that opened it, because only the
 * caller knows which card that was. The board underneath is not unmounted, so
 * closing the panel returns to the same scroll position.
 */
export function IssueDetail({ issueId, onClose, onNavigate }: IssueDetailProps) {
  const { t } = useTranslation();
  const number = useNumberFormat();
  const day = useDateFormat({ dateStyle: "medium" });
  const issue = useWorkspaceStore((s) => s.issuesById[issueId] ?? null);
  const epic = useWorkspaceStore((s) =>
    issue?.epicId ? (s.epicsById[issue.epicId] ?? null) : null,
  );
  const board = useWorkspaceStore((s) => (issue ? (s.boardsById[issue.boardId] ?? null) : null));
  const issuesById = useWorkspaceStore((s) => s.issuesById);
  const panelRef = useRef<HTMLElement>(null);
  const closeRef = useRef<HTMLButtonElement>(null);

  // Links name items by key, which is how the field document carries them.
  const byKey = useMemo(() => {
    const index = new Map<string, Issue>();
    for (const candidate of Object.values(issuesById)) index.set(candidate.key, candidate);
    return index;
  }, [issuesById]);

  // A revalidation can remove the item under an open panel. Closing beats
  // rendering a panel about nothing.
  useEffect(() => {
    if (!issue) onClose();
  }, [issue, onClose]);

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

  const onKeyDown = (event: KeyboardEvent<HTMLElement>) => {
    if (event.key === "Escape") {
      event.stopPropagation();
      onClose();
      return;
    }
    if (event.key !== "Tab" || !panelRef.current) return;
    const focusable = Array.from(panelRef.current.querySelectorAll<HTMLElement>(FOCUSABLE));
    if (focusable.length === 0) return;
    const first = focusable[0];
    const last = focusable[focusable.length - 1];
    if (event.shiftKey && document.activeElement === first) {
      event.preventDefault();
      last.focus();
    } else if (!event.shiftKey && document.activeElement === last) {
      event.preventDefault();
      first.focus();
    }
  };

  const linkGroups = [
    { key: "dependsOn", label: t("detail.dependsOn"), keys: issue.dependsOn },
    { key: "relates", label: t("detail.relates"), keys: issue.relates },
  ].filter((group) => group.keys.length > 0);

  return (
    <div className="vec-sheet-root">
      <div className="vec-scrim" aria-hidden="true" onClick={onClose} />
      <aside
        ref={panelRef}
        className="vec-sheet"
        role="dialog"
        aria-modal="true"
        aria-labelledby={titleId}
        onKeyDown={onKeyDown}
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
                {issue.storyPoints !== null ? (
                  <span className="vec-points">{number(issue.storyPoints)}</span>
                ) : (
                  "—"
                )}
              </dd>
            </div>
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
              // Plain text with its line breaks kept. Never parsed as HTML.
              <p className="vec-description">{issue.description}</p>
            ) : (
              <p className="vec-muted-note">{t("detail.noDescription")}</p>
            )}
          </section>

          <section className="vec-sheet__section" aria-labelledby={`${titleId}-links`}>
            <h3 id={`${titleId}-links`}>{t("detail.links")}</h3>
            {linkGroups.length === 0 && <p className="vec-muted-note">{t("detail.noLinks")}</p>}
            {linkGroups.map((group) => (
              <div key={group.key} className="vec-links">
                <h4>{group.label}</h4>
                <ul>
                  {group.keys.map((key) => {
                    const target = byKey.get(key);
                    return (
                      <li key={key}>
                        {target ? (
                          <button
                            type="button"
                            className="vec-link-row"
                            onClick={() => onNavigate(target.id)}
                          >
                            <LinkIcon size={14} />
                            <span className="vec-key">{target.key}</span>
                            <span className="vec-link-row__title">{target.title}</span>
                          </button>
                        ) : (
                          // A key the client never loaded: shown, not linked, so
                          // nothing on the panel leads nowhere.
                          <span className="vec-link-row vec-link-row--static">
                            <LinkIcon size={14} />
                            <span className="vec-key">{key}</span>
                            <span className="vec-link-row__title vec-muted">
                              {t("detail.notLoaded")}
                            </span>
                          </span>
                        )}
                      </li>
                    );
                  })}
                </ul>
              </div>
            ))}
          </section>
        </div>
      </aside>
    </div>
  );
}
