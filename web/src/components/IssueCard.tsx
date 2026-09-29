import { memo, type ComponentType } from "react";
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
 */
const KNOWN_TYPES: Record<string, ComponentType<{ size?: number }>> = {
  story: StoryIcon,
  bug: BugIcon,
  task: TaskIcon,
  spike: SpikeIcon,
  enabler: EnablerIcon,
};

/** Labels beyond this collapse into a "+N" chip, so a card keeps its height. */
const MAX_CARD_LABELS = 3;

export function TypeBadge({ type }: { type: string | null }) {
  const { t } = useTranslation();
  const normalized = type?.toLowerCase() ?? null;
  const known = normalized !== null && normalized in KNOWN_TYPES;
  const Glyph = known ? KNOWN_TYPES[normalized] : GenericTypeIcon;
  const label = known
    ? t(`type.${normalized}`)
    : // A type the client has no word for is user data, shown as the workspace wrote it.
      (type ?? t("type.none"));

  return (
    <span className="vec-type" data-type={known ? normalized : "other"}>
      <span className="vec-type__glyph">
        <Glyph size={14} />
      </span>
      <span className="vec-type__label">{label}</span>
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
  const hasMeta =
    epic !== null || issue.labels.length > 0 || issue.storyPoints !== null;

  return (
    <button
      type="button"
      className="vec-card"
      data-issue-id={issue.id}
      aria-haspopup="dialog"
      onClick={() => onOpen(issue.id)}
    >
      <span className="vec-card__top">
        <TypeBadge type={issue.type} />
        <span className="vec-key">{issue.key}</span>
      </span>
      <span className="vec-card__title">{issue.title}</span>
      {hasMeta && (
        <span className="vec-card__meta">
          <span className="vec-card__chips">
            {epic && <EpicChip epic={epic} />}
            {shownLabels.map((label) => (
              <span key={label} className="vec-chip">
                {label}
              </span>
            ))}
            {hiddenLabels > 0 && (
              <span className="vec-chip">
                {t("card.moreLabels", { count: number(hiddenLabels) })}
              </span>
            )}
          </span>
          {issue.storyPoints !== null && (
            <span className="vec-points">
              <span aria-hidden="true">{number(issue.storyPoints)}</span>
              <span className="vec-sr">
                {t("card.pointsLabel", { points: number(issue.storyPoints) })}
              </span>
            </span>
          )}
        </span>
      )}
    </button>
  );
});
