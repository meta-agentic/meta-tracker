import type { KeyboardEvent, PointerEvent } from "react";
import type { BarEdge } from "./roadmapModel";

export interface BarHandleProps {
  edge: BarEdge;
  label: string;
  /** The edge's day index from the timeline origin, and the formatted date it stands for. */
  day: number;
  valueText: string;
  totalDays: number;
  onDragStart: (pointerX: number) => void;
  onDragMove: (pointerX: number) => void;
  onDragEnd: (cancelled: boolean) => void;
  /** A keyboard nudge, in days. */
  onStep: (days: number) => void;
}

/**
 * One draggable end of a Gantt bar. A slider to assistive technology: its value
 * is the edge's date, and the arrow keys move it a day (a week with Shift), so
 * the date can be changed without a pointer.
 *
 * The pointer is captured on press, so the moves and the release reach this
 * handle wherever the pointer goes. Escape abandons a drag in progress.
 */
export function BarHandle({
  edge,
  label,
  day,
  valueText,
  totalDays,
  onDragStart,
  onDragMove,
  onDragEnd,
  onStep,
}: BarHandleProps) {
  const onPointerDown = (event: PointerEvent<HTMLSpanElement>) => {
    if (event.button !== 0) return;
    event.preventDefault();
    event.stopPropagation();
    event.currentTarget.setPointerCapture?.(event.pointerId);
    event.currentTarget.focus();
    onDragStart(event.clientX);
  };

  const onKeyDown = (event: KeyboardEvent<HTMLSpanElement>) => {
    const step = event.shiftKey ? 7 : 1;
    if (event.key === "ArrowLeft") onStep(-step);
    else if (event.key === "ArrowRight") onStep(step);
    else if (event.key === "Escape") onDragEnd(true);
    else return;
    event.preventDefault();
  };

  return (
    <span
      className={`vec-roadmap__handle vec-roadmap__handle--${edge}`}
      role="slider"
      tabIndex={0}
      aria-label={label}
      aria-orientation="horizontal"
      aria-valuemin={0}
      aria-valuemax={totalDays}
      aria-valuenow={day}
      aria-valuetext={valueText}
      onPointerDown={onPointerDown}
      onPointerMove={(event) => onDragMove(event.clientX)}
      onPointerUp={() => onDragEnd(false)}
      onPointerCancel={() => onDragEnd(true)}
      onKeyDown={onKeyDown}
    />
  );
}
