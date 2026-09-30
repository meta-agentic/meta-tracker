import { Component, type ErrorInfo, type ReactNode } from "react";
import { CrashFallback } from "./states";

interface ErrorBoundaryProps {
  children: ReactNode;
  /** What to show in place of the crashed tree. The whole-page fallback when omitted. */
  fallback?: (error: Error) => ReactNode;
  /** A change of this value clears a caught error and renders the children again. */
  resetKey?: unknown;
}

/**
 * Catches a render error below it and shows a designed fallback that says so,
 * instead of a blank page. Workspace data is rendered all through the tree, so
 * a value no one anticipated must not be able to take the screen down silently.
 *
 * The outermost one wraps the app, inside the providers, so its fallback is
 * themed and translated. Inner ones wrap a region — the board — so a crash
 * there leaves the shell and the board switcher working, and `resetKey` lets
 * switching to another board try again.
 */
export class ErrorBoundary extends Component<ErrorBoundaryProps, { error: Error | null }> {
  state: { error: Error | null } = { error: null };

  static getDerivedStateFromError(error: unknown) {
    return { error: error instanceof Error ? error : new Error(String(error)) };
  }

  componentDidCatch(error: unknown, info: ErrorInfo) {
    // The one diagnostic a user can hand over; nothing is sent anywhere.
    console.error(error, info.componentStack);
  }

  componentDidUpdate(previous: ErrorBoundaryProps) {
    if (this.state.error && !Object.is(previous.resetKey, this.props.resetKey)) {
      this.setState({ error: null });
    }
  }

  render() {
    const { error } = this.state;
    if (!error) return this.props.children;
    return this.props.fallback ? this.props.fallback(error) : <CrashFallback error={error} />;
  }
}
