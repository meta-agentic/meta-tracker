import { StrictMode } from "react";
import { createRoot } from "react-dom/client";
import "./styles/tokens.css";
import "./styles/shell.css";
import "./styles/board.css";
import "./styles/tree.css";
import "./styles/panels.css";
import { App } from "./App";
import type { WorkspaceSyncOptions } from "./api";
import { AppProviders } from "./providers/AppProviders";
import { ErrorBoundary } from "./components/ErrorBoundary";
import { applyPersistedThemeSync } from "./providers/applyTheme";

// Before render, not in an effect: an effect runs after the first paint, which
// is exactly the flash of the wrong theme this is here to prevent. `index.html`
// makes the same write earlier still, before this bundle is even fetched.
applyPersistedThemeSync();

function mount(sync?: WorkspaceSyncOptions) {
  createRoot(document.getElementById("root")!).render(
    <StrictMode>
      <AppProviders>
        <ErrorBoundary>
          <App sync={sync} />
        </ErrorBoundary>
      </AppProviders>
    </StrictMode>,
  );
}

// Development-only canned workspace, for looking at the UI with no server (see
// src/dev/fixtureSync.ts). `import.meta.env.DEV` is the literal `false` in a
// production build, so this branch and the module it imports are removed.
if (import.meta.env.DEV && import.meta.env.VITE_VECTIS_FIXTURES === "true") {
  void import("./dev/fixtureSync").then(({ fixtureSyncOptions }) => mount(fixtureSyncOptions()));
} else {
  mount();
}
