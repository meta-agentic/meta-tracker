import { ApiError } from "./errors";

/**
 * Where the API lives, read from the environment at build time.
 *
 * No host, port or credential is committed here. `VITE_VECTIS_API_BASE_URL` is
 * the only place a deployment names its server, and it is absent from the
 * repository by design — `web/.env.example` documents the variables without
 * carrying a value for any of them.
 *
 * The default is the *relative* path `/api/v1`, not a hostname: same-origin
 * deployments and the dev proxy both work with no configuration, and there is
 * no environment baked into the bundle. Nothing here reads or stores a
 * credential; authentication is VEC-35 and enters through `http.ts`.
 */

/** Same-origin default. Deliberately relative — it names no host. */
export const DEFAULT_BASE_URL = "/api/v1";

export const DEFAULT_TIMEOUT_MS = 10_000;

/** The workspace key format enforced by `io.vectis.domain.Workspace`. */
const WORKSPACE_KEY_FORMAT = /^[A-Z][A-Z0-9]{1,9}$/;

export interface ApiConfig {
  /** No trailing slash. */
  baseUrl: string;
  workspaceKey: string;
  timeoutMs: number;
}

/**
 * The subset of `import.meta.env` this module reads. Taking it as a parameter
 * rather than reaching for `import.meta.env` inside the body is what makes the
 * configuration testable without stubbing the module system.
 */
export interface ApiEnv {
  VITE_VECTIS_API_BASE_URL?: string;
  VITE_VECTIS_WORKSPACE_KEY?: string;
  VITE_VECTIS_API_TIMEOUT_MS?: string;
}

function trimTrailingSlash(url: string): string {
  return url.endsWith("/") && url.length > 1 ? url.slice(0, -1) : url;
}

/**
 * @throws ApiError `kind: "config"` when the environment is unusable. A missing
 * workspace key is a deployment error, and failing loudly at startup is better
 * than defaulting to some other tenant's data.
 */
export function readApiConfig(env: ApiEnv): ApiConfig {
  const rawBaseUrl = env.VITE_VECTIS_API_BASE_URL?.trim();
  const baseUrl = trimTrailingSlash(
    rawBaseUrl && rawBaseUrl.length > 0 ? rawBaseUrl : DEFAULT_BASE_URL,
  );

  const workspaceKey = env.VITE_VECTIS_WORKSPACE_KEY?.trim() ?? "";
  if (workspaceKey.length === 0) {
    throw new ApiError(
      "config",
      "VITE_VECTIS_WORKSPACE_KEY is not set: the client does not know which workspace to load",
    );
  }
  if (!WORKSPACE_KEY_FORMAT.test(workspaceKey)) {
    throw new ApiError(
      "config",
      `VITE_VECTIS_WORKSPACE_KEY must be 2-10 upper-case alphanumeric characters starting with a letter, received "${workspaceKey}"`,
    );
  }

  const rawTimeout = env.VITE_VECTIS_API_TIMEOUT_MS?.trim();
  let timeoutMs = DEFAULT_TIMEOUT_MS;
  if (rawTimeout && rawTimeout.length > 0) {
    const parsed = Number(rawTimeout);
    if (!Number.isFinite(parsed) || parsed <= 0) {
      throw new ApiError(
        "config",
        `VITE_VECTIS_API_TIMEOUT_MS must be a positive number of milliseconds, received "${rawTimeout}"`,
      );
    }
    timeoutMs = parsed;
  }

  return { baseUrl, workspaceKey, timeoutMs };
}
