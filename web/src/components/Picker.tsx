import { useEffect, useRef, useState, type KeyboardEvent, type ReactNode } from "react";
import { ChevronIcon } from "./icons";
import { ListEntry } from "./ListEntry";

export interface PickerEntry {
  /** What choosing the entry sets; "" for none. */
  value: string;
  label: string;
  icon?: ReactNode;
  decorator?: ReactNode;
  /** The decorator is part of the entry's accessible name; by default it only describes the entry. */
  decoratorInName?: boolean;
}

export interface PickerProps {
  /** The combobox's id, which the field's `<label htmlFor>` names. */
  id: string;
  entries: PickerEntry[];
  value: string;
  onChange: (value: string) => void;
  /** The id of the element that names the list: the field's label. */
  labelledBy?: string;
  describedBy?: string;
  invalid?: boolean;
  required?: boolean;
}

/** A pause this long between keys starts a new type-ahead search. */
const TYPE_AHEAD_RESET_MS = 500;

/** How an option names itself from its entry: by its label, the decorator describing it unless it is part of the name. */
function naming(optionId: string, entry: PickerEntry) {
  const label = `${optionId}-label`;
  const decorator = `${optionId}-decorator`;
  if (entry.decorator == null) return { "aria-labelledby": label };
  return entry.decoratorInName
    ? { "aria-labelledby": `${label} ${decorator}` }
    : { "aria-labelledby": label, "aria-describedby": decorator };
}

/**
 * Choose one of a list of entries, each drawn as a `ListEntry` (the WAI-ARIA
 * select-only combobox). Focus stays on the combobox and the active option is
 * announced through `aria-activedescendant`; the list exists only while open.
 *
 * Keys: closed, Down, Up, Enter and Space open it at the chosen entry, Home and
 * End at the first and the last. Open, Down and Up move, Home and End jump,
 * Enter and Space choose, Tab chooses and moves on, and Escape closes without
 * choosing and goes no further, so a sheet around the form stays open. Typing
 * moves to the next entry whose label starts with what was typed, open or not.
 * A pointer chooses by click; a press outside closes it.
 */
export function Picker(props: PickerProps) {
  const { id, entries, value } = props;
  const listId = `${id}-listbox`;
  const optionId = (index: number) => `${id}-option-${index}`;
  const [open, setOpen] = useState(false);
  const [active, setActive] = useState(0);
  const typed = useRef({ text: "", at: 0 });
  const rootRef = useRef<HTMLDivElement>(null);
  const chosen = entries.findIndex((e) => e.value === value);
  const shown: PickerEntry = chosen >= 0 ? entries[chosen] : { value, label: value };
  const withIcons = entries.some((e) => e.icon != null);

  const clamp = (index: number) => Math.max(0, Math.min(entries.length - 1, index));
  const openAt = (index: number) => {
    setActive(clamp(index));
    setOpen(true);
  };
  const choose = (index: number) => {
    const entry = entries[index];
    if (entry && entry.value !== value) props.onChange(entry.value);
    setOpen(false);
  };

  useEffect(() => {
    if (!open) return;
    const onPointerDown = (event: MouseEvent) => {
      if (!rootRef.current?.contains(event.target as Node)) setOpen(false);
    };
    document.addEventListener("mousedown", onPointerDown);
    return () => document.removeEventListener("mousedown", onPointerDown);
  }, [open]);

  useEffect(() => {
    if (open) document.getElementById(`${id}-option-${active}`)?.scrollIntoView?.({ block: "nearest" });
  }, [open, active, id]);

  /** The next entry, after the active one, whose label starts with what was typed; one letter typed again cycles. */
  const typeAhead = (char: string) => {
    const now = Date.now();
    const text = now - typed.current.at > TYPE_AHEAD_RESET_MS ? char : typed.current.text + char;
    typed.current = { text, at: now };
    const repeated = [...text].every((c) => c === text[0]);
    const needle = (repeated ? text[0] : text).toLocaleLowerCase();
    const base = open ? active : Math.max(chosen, 0);
    const from = repeated ? base + 1 : base;
    const hit = entries
      .map((_, k) => (from + k) % entries.length)
      .find((index) => entries[index].label.toLocaleLowerCase().startsWith(needle));
    if (hit !== undefined) openAt(hit);
  };

  const onKeyDown = (event: KeyboardEvent<HTMLButtonElement>) => {
    const key = event.key;
    const typing = Date.now() - typed.current.at <= TYPE_AHEAD_RESET_MS;
    if (!open) {
      if (key === "ArrowDown" || key === "ArrowUp" || key === "Enter" || key === " ") {
        event.preventDefault();
        openAt(Math.max(chosen, 0));
        return;
      }
      if (key === "Home" || key === "End") {
        event.preventDefault();
        openAt(key === "Home" ? 0 : entries.length - 1);
        return;
      }
    } else {
      const to = new Map([["ArrowDown", active + 1], ["ArrowUp", active - 1], ["Home", 0], ["End", entries.length - 1]]).get(key);
      if (to !== undefined) {
        event.preventDefault();
        setActive(clamp(to));
        return;
      }
      if (key === "Enter" || (key === " " && !typing)) {
        event.preventDefault();
        choose(active);
        return;
      }
      if (key === "Escape") {
        event.preventDefault();
        event.stopPropagation();
        setOpen(false);
        return;
      }
      if (key === "Tab") {
        choose(active);
        return;
      }
    }
    if (key.length === 1 && !event.ctrlKey && !event.metaKey && !event.altKey) {
      event.preventDefault();
      typeAhead(key);
    }
  };

  return (
    <div className="vec-picker" ref={rootRef}>
      <button
        type="button"
        id={id}
        role="combobox"
        className="vec-form__input vec-picker__trigger"
        aria-haspopup="listbox"
        aria-expanded={open}
        aria-controls={open ? listId : undefined}
        aria-activedescendant={open ? optionId(active) : undefined}
        aria-describedby={props.describedBy}
        aria-invalid={props.invalid || undefined}
        aria-required={props.required || undefined}
        onClick={() => (open ? setOpen(false) : openAt(Math.max(chosen, 0)))}
        onKeyDown={onKeyDown}
        // Space is handled on keydown; its keyup would click the button again.
        onKeyUp={(event) => event.key === " " && event.preventDefault()}
        onBlur={(event) => {
          if (!rootRef.current?.contains(event.relatedTarget as Node | null)) setOpen(false);
        }}
      >
        <ListEntry icon={shown.icon} label={shown.label} decorator={shown.decorator} reserveIcon={withIcons} />
        <ChevronIcon className="vec-picker__chevron" />
      </button>
      {open && (
        <ul role="listbox" id={listId} className="vec-picker__list" aria-labelledby={props.labelledBy} tabIndex={-1}>
          {entries.map((entry, index) => (
            <li
              key={entry.value}
              id={optionId(index)}
              role="option"
              className="vec-picker__option"
              aria-selected={entry.value === value}
              data-active={index === active || undefined}
              {...naming(optionId(index), entry)}
              onMouseDown={(event) => event.preventDefault()}
              onMouseMove={() => setActive(index)}
              onClick={() => choose(index)}
            >
              <ListEntry
                icon={entry.icon}
                label={entry.label}
                decorator={entry.decorator}
                reserveIcon={withIcons}
                labelId={`${optionId(index)}-label`}
                decoratorId={`${optionId(index)}-decorator`}
              />
            </li>
          ))}
        </ul>
      )}
    </div>
  );
}
