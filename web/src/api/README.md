# `src/api` — the workspace API client

The SPA's only route to the server. Everything here is transport: the store
(`src/store/`) never sees a wire type, and nothing outside this module builds a
URL or a header.

| File | Responsibility |
| --- | --- |
| `config.ts` | Base URL, workspace key and timeout, read from `VITE_VECTIS_*`. |
| `http.ts` | The one place a request leaves the client. Timeouts, aborts, status. |
| `wire.ts` | The server's shapes, and the narrowing from `unknown` into them. |
| `client.ts` | The workspace, board and item reads, plus the snapshot fan-out. |
| `adapter.ts` | Wire shape → the store's `WorkspaceSnapshot`. |
| `useWorkspaceSync.ts` | Cache-first hydration and the loading/error states. |

Configuration lives in the environment and nowhere else; `web/.env.example`
documents the variables and carries a value for none of them. The default base
URL is the relative path `/api/v1`, which names no host, so no environment is
baked into the bundle. Authentication is VEC-35: every header is built in one
function in `http.ts`, so it arrives there without a call site changing.

## The contract: VEC-94 under ADR-VEC-05

`client.ts` reads the three VEC-94 resources under `/api/v1`, and `wire.ts` decodes them field for field:

- `GET /workspaces/{key}`: `{key, name, template: {key, version}}`.
- `GET /workspaces/{key}/boards`: `[{key, name, columns: [{key, name, ordinal, onBoard}]}]`.
- `GET /workspaces/{key}/items?limit=&cursor=`: `{items: [{key, title, board, column, rank, fields, version}], next}`, newest first. `listItems` follows `next` until it is null, 200 items a page (the server's maximum); a cursor handed out twice is a format error rather than an endless loop.

No body carries an id (ADR-VEC-05 §3), so the store's ids are keys: a board's id is its key, an item's id is its key, and a column's id is its key, which is unique within its board, the only scope the store looks a column up in. A column with `onBoard: false` is left off the board; its items stay issues and show in the backlog.

Every error is `application/problem+json` (RFC 9457). `http.ts` reads it into `ApiError.problem` (`type`, `title`, `status`, `detail`) and makes the problem's own sentence the error message, which is what the error state shows. A body that is not a problem leaves the plain status error standing. A 404, such as an unknown workspace, is not retryable, so the error state offers no dead retry.

What the contract leaves open is the `fields` document, and the adapter's reading of it remains an assumption, each fixed in one place in `adapter.ts`:

- **Ordering.** `assignOrders` sorts each column by `rank` and projects to the dense `Issue.order` the selectors sort on, tie-breaking on `key` so the result does not depend on the newest-first response order.
- **Epics.** There is no Epic record. An item with `fields.type === "epic"` (`FIELD.type`, `EPIC_TYPE`) becomes an `Epic`, and every other item points at one by key through `fields.parentId` (`FIELD.parentId`). A parent that is not an epic in the same payload is dropped to `null`.
- **The long tail.** `startDate`, `dueDate`, `storyPoints`, the item type, `labels`, `priority`, `description`, and the links `dependencies` and `relates` (item keys) are read out of `fields`. A list keeps only its distinct non-empty strings; an absent or malformed value is empty or `null`, never guessed.

An item naming a board the response did not carry is dropped, because admitting it would put an unreachable id into `boardIssueIds`.

## Testing

The suite drives the real client, the real decoders and the real adapter over the
recorded transport in `src/test/recordedTransport.ts`. No test starts a server
and none should: the profiling presets must keep running with nothing listening
on the API port. `createGate()` holds a response open so an in-flight state is
observed rather than raced.

Do not give the client a live default host to make a test pass. The recorded
transport is the test double.
