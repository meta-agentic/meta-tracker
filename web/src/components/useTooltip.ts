import { useCallback, useEffect, useId, useRef, useState, type FocusEvent, type KeyboardEvent } from "react";

/**
 * How long the pointer rests on a control before its tooltip shows: half of
 * Chromium's own tooltip delay, 500 ms on Windows, Linux and ChromeOS. On macOS
 * a native `title` tooltip waits for the system's delay instead. Focus shows the
 * tooltip at once.
 */
export const TOOLTIP_DELAY_MS = 250;

/**
 * A tooltip for a control whose visible content is an icon alone (the WAI-ARIA
 * tooltip pattern): it shows after {@link TOOLTIP_DELAY_MS} on hover and at once
 * when the control takes focus, and hides on blur, when the pointer leaves, and
 * on Escape.
 * Spread `triggerProps` on the element that is hovered and contains the focusable
 * control, put `describedBy` on that control, and draw `<Tooltip>` inside the
 * trigger. The control keeps its own accessible name; the tooltip describes it.
 */
export function useTooltip() {
  const id = useId();
  const [open, setOpen] = useState(false);
  const timer = useRef<number | undefined>(undefined);

  const cancel = useCallback(() => {
    window.clearTimeout(timer.current);
    timer.current = undefined;
  }, []);
  useEffect(() => cancel, [cancel]);

  const triggerProps = {
    onMouseEnter: () => {
      cancel();
      timer.current = window.setTimeout(() => setOpen(true), TOOLTIP_DELAY_MS);
    },
    onMouseLeave: (event: { currentTarget: HTMLElement }) => {
      cancel();
      if (!event.currentTarget.contains(document.activeElement)) setOpen(false);
    },
    onFocus: () => {
      cancel();
      setOpen(true);
    },
    onBlur: (event: FocusEvent<HTMLElement>) => {
      if (!event.currentTarget.contains(event.relatedTarget as Node | null)) {
        cancel();
        setOpen(false);
      }
    },
    // Escape dismisses an open tooltip and goes no further, so a sheet around the
    // form stays open; with no tooltip showing, Escape reaches the sheet as before.
    onKeyDown: (event: KeyboardEvent<HTMLElement>) => {
      if (event.key === "Escape" && open) {
        event.stopPropagation();
        cancel();
        setOpen(false);
      }
    },
  };

  return { id, open, describedBy: open ? id : undefined, triggerProps };
}
