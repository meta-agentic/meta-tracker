import { useMemo } from "react";
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
    setFilter({ ...filter, [facet]: on ? [...rest, value] : rest });
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
          value={filter.text}
          placeholder={t("filter.text")}
          maxLength={200}
          onChange={(event) => setFilter({ ...filter, text: event.target.value })}
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
        disabled={!active}
        onClick={() => setFilter(EMPTY_FILTER)}
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
