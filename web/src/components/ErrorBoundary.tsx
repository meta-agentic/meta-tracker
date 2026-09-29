import { Component, type ErrorInfo, type ReactNode } from "react";
import { CrashFallback } from "./states";

/**
 * The last line of defence: a render error anywhere below replaces the app
 * with a designed page that says so, instead of a blank one. Workspace data is
 * rendered all through the tree, so a value no one anticipated must not be able
 * to take the whole screen down silently.
 *
 * Sits inside the providers, so the fallback is themed and translated.
 */
export class ErrorBoundary extends Component<{ children: ReactNode }, { error: Error | null }> {
  state: { error: Error | null } = { error: null };

  static getDerivedStateFromError(error: unknown) {
    return { error: error instanceof Error ? error : new Error(String(error)) };
  }

  componentDidCatch(error: unknown, info: ErrorInfo) {
    // The one diagnostic a user can hand over; nothing is sent anywhere.
    console.error(error, info.componentStack);
  }

  render() {
    return this.state.error ? <CrashFallback error={this.state.error} /> : this.props.children;
  }
}
