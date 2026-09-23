import { ApiError } from "./errors";

/**
 * The single place every request leaves the client.
 *
 * `Transport` is `fetch`'s shape rather than `fetch` itself, so the suite drives
 * the real client against a recorded transport with no server and no global
 * patching. It is also the seam VEC-35 needs: an `Authorization` header is added
 * by editing `buildHeaders` below, and no call site changes.
 */
export type Transport = (url: string, init: RequestInit) => Promise<Response>;

export interface HttpClientOptions {
  /** Absolute or root-relative, without a trailing slash. */
  baseUrl: string;
  timeoutMs: number;
  /** Defaults to the global `fetch`. */
  transport?: Transport;
}

export interface HttpClient {
  /** GETs `path` relative to the base URL and returns the parsed JSON body. */
  getJson(path: string, signal?: AbortSignal): Promise<unknown>;
}

function buildHeaders(): HeadersInit {
  // VEC-35 adds the Authorization header here. Nothing else in the client
  // constructs headers, so that stays a one-line change.
  return { Accept: "application/json" };
}

function defaultTransport(url: string, init: RequestInit): Promise<Response> {
  if (typeof fetch !== "function") {
    return Promise.reject(
      new ApiError("network", "no fetch implementation is available in this environment"),
    );
  }
  return fetch(url, init);
}

/**
 * Bounds the request and forwards an outer cancellation, without leaving a
 * timer behind: a caller that aborts mid-flight must not be woken later by this
 * request's timeout.
 */
function withTimeout(
  timeoutMs: number,
  outer: AbortSignal | undefined,
): { signal: AbortSignal; done: () => void; timedOut: () => boolean } {
  const controller = new AbortController();
  let expired = false;

  const timer = setTimeout(() => {
    expired = true;
    controller.abort();
  }, timeoutMs);

  const forward = () => controller.abort();
  if (outer) {
    if (outer.aborted) forward();
    else outer.addEventListener("abort", forward, { once: true });
  }

  return {
    signal: controller.signal,
    done: () => {
      clearTimeout(timer);
      outer?.removeEventListener("abort", forward);
    },
    timedOut: () => expired,
  };
}

export function createHttpClient(options: HttpClientOptions): HttpClient {
  const { baseUrl, timeoutMs } = options;
  const transport = options.transport ?? defaultTransport;

  return {
    async getJson(path, signal) {
      const url = `${baseUrl}${path}`;
      const bound = withTimeout(timeoutMs, signal);

      let response: Response;
      try {
        response = await transport(url, {
          method: "GET",
          headers: buildHeaders(),
          signal: bound.signal,
        });
      } catch (cause) {
        if (bound.timedOut()) {
          throw new ApiError("timeout", `GET ${url} timed out after ${timeoutMs}ms`, {
            cause,
          });
        }
        // An outer abort is the caller's own decision, not a failure to report.
        if (signal?.aborted) throw cause;
        throw new ApiError("network", `GET ${url} could not reach the server`, { cause });
      } finally {
        bound.done();
      }

      if (!response.ok) {
        throw new ApiError(
          "http",
          `GET ${url} returned ${response.status} ${response.statusText}`.trimEnd(),
          { status: response.status },
        );
      }

      try {
        return (await response.json()) as unknown;
      } catch (cause) {
        throw new ApiError("format", `GET ${url} returned a body that is not JSON`, {
          cause,
        });
      }
    },
  };
}
