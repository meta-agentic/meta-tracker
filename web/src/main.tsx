import { StrictMode } from "react";
import { createRoot } from "react-dom/client";
import "./styles/tokens.css";
import { App } from "./App";
import { AppProviders } from "./providers/AppProviders";
import { applyPersistedThemeSync } from "./providers/applyTheme";

// Before render, not in an effect: an effect runs after the first paint, which
// is exactly the flash of the wrong theme this is here to prevent. `index.html`
// makes the same write earlier still, before this bundle is even fetched.
applyPersistedThemeSync();

createRoot(document.getElementById("root")!).render(
  <StrictMode>
    <AppProviders>
      <App />
    </AppProviders>
  </StrictMode>,
);
