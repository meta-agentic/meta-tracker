import { uiTracking } from "./cache";

/**
 * Columns on the board last shown, so a cold load draws that many and the
 * layout does not jump when the data lands. Before any board has been shown,
 * the default board template's five.
 */
const BOARD_COLUMNS_KEY = "ui:boardColumnCount";
const DEFAULT_BOARD_COLUMNS = 5;
const MAX_SKELETON_COLUMNS = 12;

export function rememberBoardColumnCount(count: number) {
  if (count > 0) uiTracking.write(BOARD_COLUMNS_KEY, count);
}

export function skeletonColumnCount(): number {
  const count = uiTracking.read<unknown>(BOARD_COLUMNS_KEY, DEFAULT_BOARD_COLUMNS);
  return Number.isInteger(count) && (count as number) > 0
    ? Math.min(count as number, MAX_SKELETON_COLUMNS)
    : DEFAULT_BOARD_COLUMNS;
}
