import { useEffect, useRef, type RefObject } from "react";

const FOCUSABLE =
  'button:not([disabled]), [href], input:not([disabled]), select:not([disabled]), textarea:not([disabled]), [tabindex]:not([tabindex="-1"])';

/**
 * Makes the sheet modal for as long as it is mounted.
 *
 * Everything else on the page is made `inert` — not focusable, not clickable,
 * hidden from assistive technology — so focus cannot leave the sheet by a click
 * or a Tab. Escape and Tab are handled at the document, not on the sheet, so
 * they still work when focus sits somewhere a keydown on the sheet would never
 * see. Only the `inert` attributes this added are removed again.
 */
export function useModal(
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

