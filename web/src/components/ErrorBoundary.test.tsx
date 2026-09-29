import { afterEach, describe, expect, it, vi } from "vitest";
import { render, screen } from "@testing-library/react";
import { ErrorBoundary } from "./ErrorBoundary";
import { AppProviders } from "../providers/AppProviders";

function Boom(): never {
  throw new Error("a value nobody anticipated");
}

afterEach(() => {
  vi.restoreAllMocks();
});

describe("ErrorBoundary", () => {
  it("replaces a crashed tree with a designed page instead of a blank one", () => {
    // React logs the caught error and, in development, re-dispatches it as a
    // window error; both are expected here, so keep the test output clean.
    vi.spyOn(console, "error").mockImplementation(() => {});
    const swallow = (event: ErrorEvent) => event.preventDefault();
    window.addEventListener("error", swallow);

    render(
      <AppProviders>
        <ErrorBoundary>
          <Boom />
        </ErrorBoundary>
      </AppProviders>,
    );

    const alert = screen.getByRole("alert");
    expect(alert).toHaveTextContent("Something went wrong.");
    expect(alert).toHaveTextContent("a value nobody anticipated");
    expect(screen.getByRole("button", { name: "Reload" })).toBeInTheDocument();
    window.removeEventListener("error", swallow);
  });

  it("renders its children when nothing throws", () => {
    render(
      <AppProviders>
        <ErrorBoundary>
          <p>fine</p>
        </ErrorBoundary>
      </AppProviders>,
    );
    expect(screen.getByText("fine")).toBeInTheDocument();
  });
});
