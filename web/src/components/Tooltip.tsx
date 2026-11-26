/**
 * The bubble `useTooltip` shows: positioned over its trigger, so showing
 * it moves nothing else, and drawn only while open.
 */
export function Tooltip({ id, open, text }: { id: string; open: boolean; text: string }) {
  return open ? (
    <span role="tooltip" id={id} className="vec-tooltip">
      {text}
    </span>
  ) : null;
}
