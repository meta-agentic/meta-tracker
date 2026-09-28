import { describe, expect, it } from "vitest";
import { DEFAULT_BASE_URL, DEFAULT_TIMEOUT_MS, readApiConfig } from "./config";
import { ApiError } from "./errors";

const valid = { VITE_VECTIS_WORKSPACE_KEY: "VEC" };

describe("readApiConfig", () => {
  it("defaults to the same-origin path, naming no host", () => {
    const config = readApiConfig(valid);
    expect(config.baseUrl).toBe(DEFAULT_BASE_URL);
    expect(config.baseUrl.startsWith("/")).toBe(true);
    expect(config.timeoutMs).toBe(DEFAULT_TIMEOUT_MS);
  });

  it("takes the base URL from the environment and drops a trailing slash", () => {
    const config = readApiConfig({
      ...valid,
      VITE_VECTIS_API_BASE_URL: "https://tracker.example.test/api/v1/",
    });
    expect(config.baseUrl).toBe("https://tracker.example.test/api/v1");
  });

  it("refuses to run without a workspace key", () => {
    expect(() => readApiConfig({})).toThrowError(ApiError);
    expect(() => readApiConfig({ VITE_VECTIS_WORKSPACE_KEY: "  " })).toThrowError(
      /VITE_VECTIS_WORKSPACE_KEY is not set/,
    );
  });

  it("rejects a workspace key the server would reject", () => {
    expect(() => readApiConfig({ VITE_VECTIS_WORKSPACE_KEY: "vec" })).toThrowError(
      /upper-case alphanumeric/,
    );
    expect(() => readApiConfig({ VITE_VECTIS_WORKSPACE_KEY: "V" })).toThrowError(ApiError);
    expect(() =>
      readApiConfig({ VITE_VECTIS_WORKSPACE_KEY: "VECTISWORKSPACE" }),
    ).toThrowError(ApiError);
  });

  it("rejects a non-positive timeout instead of silently disabling the bound", () => {
    expect(() =>
      readApiConfig({ ...valid, VITE_VECTIS_API_TIMEOUT_MS: "0" }),
    ).toThrowError(ApiError);
    expect(() =>
      readApiConfig({ ...valid, VITE_VECTIS_API_TIMEOUT_MS: "soon" }),
    ).toThrowError(ApiError);
  });

  it("carries a configuration failure as a non-retryable ApiError", () => {
    try {
      readApiConfig({});
      expect.unreachable("expected a config error");
    } catch (error) {
      expect(error).toBeInstanceOf(ApiError);
      expect((error as ApiError).kind).toBe("config");
    }
  });
});
