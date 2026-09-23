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

## Contract assumptions — verify against VEC-10

**This module was written against a contract that does not exist yet.** VEC-10
owns the endpoint definitions and has not landed; `vectis-server` exposes no
read endpoints at all. The wire types are therefore *derived* from the server
aggregates in `vectis-domain` — `Workspace`, `Board`, `BoardColumn`, `Item` —
which any provisioning endpoint has to serialize, rather than taken from a
published specification.

VEC-42 is not realized until this list has been checked against the endpoints
VEC-10 actually publishes. A consumer built against an assumed provider is not
realized, whatever its own tests say — so treat this as the work remaining, not
as documentation of work done.

Each item below names the single place a disagreement is fixed.

- [ ] **Ordering: `Item.rank` → `Issue.order`.** The domain orders within a
      column by a sparse lexicographic string so a drag updates one row;
      `store/selectors.ts` sorts on a dense integer. `assignOrders` in
      `adapter.ts` sorts by rank per column and projects to indices, tie-breaking
      on `key` so the result does not depend on response order. Verify the server
      really exposes `rank`, and that it does not also expose a dense position
      that should be preferred.

- [ ] **Epics: there is no Epic record.** The domain has exactly one work-unit
      type, so hierarchy lives in the open `fields` document. An item carrying
      `fields.type === "epic"` is lifted out of the issue list into `Epic`, and
      every other item points at one through `fields.parentId`. A parent that is
      not an epic in the same payload is dropped to `null`. The two field names
      and the marker value are `FIELD.type`, `FIELD.parentId` and `EPIC_TYPE` in
      `adapter.ts`. Verify against whatever VEC-10 says about parent/child, and
      in particular whether epics arrive on a separate endpoint instead.

- [ ] **The long tail: schedule and estimate.** `startDate`, `dueDate` and
      `storyPoints` are not relational columns on `Item`. They are read out of
      `fields` and are `null` when absent or malformed, never guessed. The keys
      are `FIELD.startDate`, `FIELD.dueDate` and `FIELD.storyPoints` in
      `adapter.ts`. Verify the names and the date encoding.

One prerequisite that is assumed rather than derived, and so belongs on the same
checklist: the **endpoint paths and their envelopes**. `client.ts` addresses
`/workspaces/{key}`, `/workspaces/{key}/boards` and `/workspaces/{key}/items`,
each returning a bare object or array with no pagination envelope. Nothing in
the domain fixes those, and a paginated item collection would change
`fetchWorkspaceSnapshot` rather than the adapter.

Two further assumptions are small but real: `Board` has no key of its own, so the
adapter takes the workspace key (item keys are minted from that prefix); and an
item naming a board the response did not carry is dropped, because admitting it
would put an unreachable id into `boardIssueIds`.

## Testing

The suite drives the real client, the real decoders and the real adapter over the
recorded transport in `src/test/recordedTransport.ts`. No test starts a server
and none should: the profiling presets must keep running with nothing listening
on the API port. `createGate()` holds a response open so an in-flight state is
observed rather than raced.

Do not give the client a live default host to make a test pass. The recorded
transport is the test double.
