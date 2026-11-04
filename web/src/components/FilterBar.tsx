import { useCallback, useEffect, useMemo, useRef, useState } from "react";
import { useTranslation } from "react-i18next";
import { useWorkspaceStore } from "../store/workspaceStore";
import {
  EMPTY_FILTER,
  FACETS,
  filterIssues,
  isFilterActive,
  useBoardFilter,
  type Facet,
} from "../store/boardFilter";
import { useNumberFormat } from "../i18n/format";
import type { Issue } from "../store/types";
import { boardIssues, selectActiveBoard } from "../store/selectors";
import { SearchIcon } from "./icons";

export interface FilterBarProps {
  /**
   * What the bar counts and offers values from. The tabs load different sets,
   * so the caller names it: the board's items, or the open sprint's items on
   * the board.
   */
  items: readonly Issue[];
  scope: "board" | "sprint";
}

/** How long typing pauses before the text reaches the URL. */
const WRITE_DELAY_MS = 250;

/**
 * The bar over the active board's items. `onBoardOnly` leaves out items in
 * columns the board does not show, for the Boards tab, which cannot render them.
 * The Backlog lists every item, so it counts them all.
 */
export function BoardFilterBar({ onBoardOnly = false }: { onBoardOnly?: boolean }) {
  const activeBoardId = useWorkspaceStore((s) => s.activeBoardId);
  const board = useWorkspaceStore(selectActiveBoard);
  const issuesById = useWorkspaceStore((s) => s.issuesById);
  const boardIssueIds = useWorkspaceStore((s) => s.boardIssueIds);
  const items = useMemo(() => {
    const all = activeBoardId ? boardIssues(issuesById, boardIssueIds, activeBoardId) : [];
    if (!onBoardOnly) return all;
    const shown = new Set(board?.columns.map((column) => column.id));
    return all.filter((issue) => shown.has(issue.columnId));
  }, [activeBoardId, board, issuesById, boardIssueIds, onBoardOnly]);
  return <FilterBar items={items} scope="board" />;
}

interface Option {
  value: string;
  label: string;
}

/**
 * The filter above the Boards, Sprint and Backlog tabs. It only edits the
 * filter in the URL; each tab applies it to what it renders.
 */
export function FilterBar({ items, scope }: FilterBarProps) {
  const { t } = useTranslation();
  const number = useNumberFormat();
  const epicsById = useWorkspaceStore((s) => s.epicsById);
  const [filter, setFilter] = useBoardFilter();
  const active = isFilterActive(filter);

  // The text box keeps its own copy and reaches the URL after a pause: the URL
  // is written with history.replaceState, which browsers rate-limit, and a
  // write per keystroke would hit that limit while the user is still typing.
  const [draft, setDraft] = useState(filter.text);
  const timer = useRef<ReturnType<typeof setTimeout> | null>(null);
  const cancelWrite = useCallback(() => {
    if (timer.current !== null) clearTimeout(timer.current);
    timer.current = null;
  }, []);
  // The URL changed from elsewhere (clear, back button): follow it, unless a
  // write of newer typing is still waiting.
  useEffect(() => {
    if (timer.current === null) setDraft(filter.text);
  }, [filter.text]);
  useEffect(() => cancelWrite, [cancelWrite]);
  const typed = (text: string) => {
    setDraft(text);
    cancelWrite();
    timer.current = setTimeout(() => {
      timer.current = null;
      setFilter({ ...filter, text });
    }, WRITE_DELAY_MS);
  };

  const matching = useMemo(() => filterIssues(items, epicsById, filter).length, [items, epicsById, filter]);

  // Values on offer come from the items, plus any the URL already holds, so a
  // value from a shared link can always be unticked.
  const options = useMemo(() => {
    const collect = (facet: Facet): Option[] => {
      const seen = new Map<string, Option>();
      const add = (value: string | null | undefined, label = value) => {
        if (value && !seen.has(value.toLowerCase())) seen.set(value.toLowerCase(), { value, label: label ?? value });
      };
      for (const issue of items) {
        const epic = issue.epicId ? epicsById[issue.epicId] : undefined;
        if (facet === "epic") add(epic?.key, epic ? `${epic.key} ${epic.title}` : undefined);
        else if (facet === "label") (issue.labels ?? []).forEach((l) => add(l));
        else if (facet === "kind") add(issue.type);
        else add(issue.priority);
      }
      for (const value of filter[facet]) add(value);
      return [...seen.values()].sort((a, b) => a.value.localeCompare(b.value, undefined, { numeric: true }));
    };
    return Object.fromEntries(FACETS.map((facet) => [facet, collect(facet)])) as Record<Facet, Option[]>;
  }, [items, epicsById, filter]);

  const toggle = (facet: Facet, value: string, on: boolean) => {
    const rest = filter[facet].filter((v) => v.toLowerCase() !== value.toLowerCase());
    cancelWrite();
    setFilter({ ...filter, text: draft, [facet]: on ? [...rest, value] : rest });
  };

  return (
    <form
      className="vec-filter"
      role="search"
      aria-label={t("filter.label")}
      onSubmit={(event) => event.preventDefault()}
    >
      <label className="vec-filter__search">
        <SearchIcon size={14} />
        <span className="vec-sr">{t("filter.text")}</span>
        <input
          type="search"
          className="vec-filter__input"
          value={draft}
          placeholder={t("filter.text")}
          maxLength={200}
          onChange={(event) => typed(event.target.value)}
        />
      </label>
      {FACETS.map((facet) => (
        <details key={facet} className="vec-filter__facet">
          <summary className="vec-button">
            {t(`filter.facet.${facet}`)}
            {filter[facet].length > 0 && (
              <span className="vec-count">{number(filter[facet].length)}</span>
            )}
          </summary>
          <fieldset className="vec-filter__options">
            <legend className="vec-sr">{t(`filter.facet.${facet}`)}</legend>
            {options[facet].length === 0 && (
              <p className="vec-muted">{t("filter.noValues")}</p>
            )}
            {options[facet].map((option) => (
              <label key={option.value} className="vec-filter__option">
                <input
                  type="checkbox"
                  checked={filter[facet].some((v) => v.toLowerCase() === option.value.toLowerCase())}
                  onChange={(event) => toggle(facet, option.value, event.target.checked)}
                />
                {/* Epic titles, labels, kinds and priorities are user data, not chrome. */}
                <span>{option.label}</span>
              </label>
            ))}
          </fieldset>
        </details>
      ))}
      <button
        type="button"
        className="vec-button"
        disabled={!active && draft === ""}
        onClick={() => {
          cancelWrite();
          setDraft("");
          setFilter(EMPTY_FILTER);
        }}
      >
        {t("filter.clear")}
      </button>
      <p className="vec-filter__count" aria-live="polite" data-testid="filter-count">
        {t(`filter.count.${scope}`, {
          count: items.length,
          matching: number(matching),
          total: number(items.length),
        })}
      </p>
    </form>
  );
}
