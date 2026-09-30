import { memo } from "react";
import { useTranslation } from "react-i18next";
import type { Epic, Issue } from "../store/types";
import { useNumberFormat } from "../i18n/format";
import {
  BugIcon,
  EnablerIcon,
  GenericTypeIcon,
  SpikeIcon,
  StoryIcon,
  TaskIcon,
} from "./icons";

/**
 * Types the client has a glyph and a translation for. Anything else the field
 * document carries is still shown — under its own name and a neutral glyph —
 * because the type vocabulary is the workspace's, not the client's.
 *
 * A Set and a switch, not a lookup on an object literal: the type is workspace
 * data, and an object would find `constructor` or `__proto__` on its prototype.
 */
const KNOWN_TYPES: ReadonlySet<string> = new Set(["story", "bug", "task", "spike", "enabler"]);

function TypeGlyph({ type }: { type: string | null }) {
  switch (type) {
    case "story":
      return <StoryIcon size={14} />;
    case "bug":
      return <BugIcon size={14} />;
    case "task":
      return <TaskIcon size={14} />;
    case "spike":
      return <SpikeIcon size={14} />;
    case "enabler":
      return <EnablerIcon size={14} />;
    default:
      return <GenericTypeIcon size={14} />;
  }
}

/** Labels beyond this collapse into a "+N" chip, so the card's last row stays one line. */
const MAX_CARD_LABELS = 2;

/**
 * An item's type as a glyph and a word. `compact` keeps the word for assistive
 * technology and the tooltip only — on a card the glyph is enough, and a word
 * on every card is noise.
 */
export function TypeBadge({ type, compact = false }: { type: string | null; compact?: boolean }) {
  const { t } = useTranslation();
  const normalized = type?.toLowerCase() ?? null;
  const known = normalized !== null && KNOWN_TYPES.has(normalized);
  const label = known
    ? t(`type.${normalized}`)
    : // A type the client has no word for is user data, shown as the workspace wrote it.
      (type ?? t("type.none"));

  return (
    <span
      className="vec-type"
      data-type={known ? normalized : "other"}
      title={compact ? label : undefined}
    >
      <span className="vec-type__glyph">
        <TypeGlyph type={known ? normalized : null} />
      </span>
      <span className={compact ? "vec-sr" : "vec-type__label"}>{label}</span>
    </span>
  );
}

export function EpicChip({ epic }: { epic: Epic }) {
  return (
    <span className="vec-chip vec-chip--epic" title={`${epic.key} · ${epic.title}`}>
      {/* The epic colour is workspace data, so it arrives as a value, not a token.
          backgroundColor, not background: a colour property cannot load a url(). */}
      <span className="vec-chip__dot" style={{ backgroundColor: epic.color }} aria-hidden="true" />
      <span className="vec-chip__text">{epic.title}</span>
    </span>
  );
}

export interface IssueCardProps {
  issue: Issue;
  epic: Epic | null;
  onOpen: (issueId: string) => void;
}

/**
 * One board card. The whole card is a single button: one tab stop per item,
 * Enter or Space opens the detail panel, and its accessible name reads in the
 * same order as the card — type, key, title, then the metadata.
 *
 * Memoized so that opening or closing the detail panel, which re-renders the
 * board around it, does not re-render every card on the board.
 */
export const IssueCard = memo(function IssueCard({ issue, epic, onOpen }: IssueCardProps) {
  const { t } = useTranslation();
  const number = useNumberFormat();
  const shownLabels = issue.labels.slice(0, MAX_CARD_LABELS);
  const hiddenLabels = issue.labels.length - shownLabels.length;
  const hasMeta = epic !== null || issue.labels.length > 0;

  return (
    <button
      type="button"
      className="vec-card"
      data-issue-id={issue.id}
      aria-haspopup="dialog"
      onClick={() => onOpen(issue.id)}
    >
      <span className="vec-card__top">
        <TypeBadge type={issue.type} compact />
        <span className="vec-key">{issue.key}</span>
        {issue.storyPoints !== null && (
          <span className="vec-points">
            <span aria-hidden="true">{number(issue.storyPoints)}</span>
            <span className="vec-sr">
              {t("card.pointsLabel", {
                points: number(issue.storyPoints),
                count: issue.storyPoints,
              })}
            </span>
          </span>
        )}
      </span>
      <span className="vec-card__title">{issue.title}</span>
      {hasMeta && (
        <span className="vec-card__meta">
          {epic && <EpicChip epic={epic} />}
          {shownLabels.map((label) => (
            <span key={label} className="vec-chip" title={label}>
              <span className="vec-chip__text">{label}</span>
            </span>
          ))}
          {hiddenLabels > 0 && (
            <span className="vec-chip vec-chip--more">
              {t("card.moreLabels", { count: number(hiddenLabels) })}
            </span>
          )}
        </span>
      )}
    </button>
  );
});
