import { workspaceStore } from "../store/workspaceStore";
import { resolveTheme } from "../store/preferences";

/**
 * Applies the persisted theme and locale to <html> once, synchronously.
 *
 * `index.html` does this before the bundle is fetched, which is what actually
 * prevents the flash. This is the same write for the case where the bundle runs
 * without that document (a test, an embed, a future SSR shell), so no consumer
 * depends on the inline script being present to get the right first paint.
 */
export function applyPersistedThemeSync(): void {
  if (typeof document === "undefined") return;
  const { theme, locale } = workspaceStore.getState();
  const resolved = resolveTheme(theme);
  document.documentElement.dataset.theme = resolved;
  document.documentElement.style.colorScheme = resolved;
  document.documentElement.lang = locale;
}
