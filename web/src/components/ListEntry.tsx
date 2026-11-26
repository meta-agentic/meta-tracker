import type { ReactNode } from "react";

export interface ListEntryProps {
  /** The entry's name: drawn unless `show` is "icon", and always its accessible name. */
  label: string;
  /** A decorative glyph before the label, in a column of fixed width. */
  icon?: ReactNode;
  /** Extra text after the label, right-aligned and never wrapped: an item key, a state, points, a check. */
  decorator?: ReactNode;
  /**
   * "both" (the default) draws the icon and the label; "icon" draws the icon
   * alone, and the control around it carries the label as its accessible name
   * and its tooltip.
   */
  show?: "both" | "icon";
  /** Keep the icon column when this entry has no icon, so a list of entries stays aligned. */
  reserveIcon?: boolean;
  /** Ids for the label and the decorator, so the element around the entry can name itself by them. */
  labelId?: string;
  decoratorId?: string;
}

/**
 * One entry of a list or a choice (ADR-VEC-03, amendment 1): an optional icon,
 * its label and an optional trailing decorator. The label takes the room left
 * and ends in an ellipsis when it does not fit, so its full text stays in the
 * DOM and in the accessible name; the decorator keeps its width and never wraps.
 *
 * The entry draws; the element around it names. A host names itself by
 * `labelId`, and leaves the decorator out of its name, as a description by
 * `decoratorId`, unless it says otherwise (see `Picker`). With `show="icon"`
 * the host takes `label` as its `aria-label` and shows it in a tooltip.
 */
export function ListEntry({ label, icon, decorator, show = "both", reserveIcon, labelId, decoratorId }: ListEntryProps) {
  return (
    <span className="vec-entry" data-show={show}>
      {(icon || reserveIcon) && (
        <span className="vec-entry__icon" aria-hidden="true">
          {icon}
        </span>
      )}
      {show === "both" && (
        <span className="vec-entry__label" id={labelId}>
          {label}
        </span>
      )}
      {show === "both" && decorator != null && (
        <span className="vec-entry__decorator" id={decoratorId}>
          {decorator}
        </span>
      )}
    </span>
  );
}
