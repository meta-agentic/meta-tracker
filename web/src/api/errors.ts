/**
 * One error type for everything that can go wrong between the store and the
 * server, so a call site distinguishes "retry might help" from "it never will"
 * without pattern-matching on messages.
 */
export type ApiErrorKind =
  /** The client is misconfigured — no base URL, no workspace key. Retrying will not help. */
  | "config"
  /** The request never reached a response: DNS, TCP, CORS, offline. */
  | "network"
  /** The request was abandoned before the server answered. */
  | "timeout"
  /** A response arrived carrying a non-2xx status. */
  | "http"
  /** A 2xx response arrived whose body is not the shape the contract promises. */
  | "format";

/**
 * The RFC 9457 `application/problem+json` body every `/api/v1` error carries
 * (ADR-VEC-05). `detail` is the server's sentence about this occurrence, such as
 * which workspace does not exist.
 */
export interface Problem {
  type: string;
  title: string;
  status: number;
  detail: string | null;
}

export class ApiError extends Error {
  readonly kind: ApiErrorKind;
  /** Present only for `kind: "http"`. */
  readonly status?: number;
  /** Present when a `kind: "http"` response carried a problem+json body. */
  readonly problem?: Problem;

  constructor(
    kind: ApiErrorKind,
    message: string,
    options: { status?: number; problem?: Problem; cause?: unknown } = {},
  ) {
    super(message, { cause: options.cause });
    this.name = "ApiError";
    this.kind = kind;
    this.status = options.status;
    this.problem = options.problem;
  }
}

/**
 * True when re-issuing the same request could plausibly succeed. A malformed
 * response or a missing workspace key will fail identically forever, so the
 * error UI offers a retry only for the transient kinds.
 */
export function isRetryable(error: unknown): boolean {
  if (!(error instanceof ApiError)) return true;
  switch (error.kind) {
    case "network":
    case "timeout":
      return true;
    case "http":
      // 4xx is the client asking for something that does not exist or is not
      // permitted; repeating it changes nothing. 5xx may be transient.
      return error.status === undefined || error.status >= 500;
    case "config":
    case "format":
      return false;
  }
}
