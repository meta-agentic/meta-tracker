# ADR-VEC-05 — The `/api/v1` conventions: one problem shape, status codes, keys in paths, revisions as ETags

| | |
|---|---|
| **Status** | Accepted (2026-10-28), approved by the PO as written |
| **Date** | 2026-10-28 |
| **Deciders** | Product Owner |
| **Requirement** | [ADR-VEC-01](./ADR-VEC-01-product-requirements-and-features.md) R-CORE-5 (template → instance); [ADR-VEC-03](./ADR-VEC-03-workflow-and-template-model.md), whose resolver reports the violations this record carries |
| **Evidence** | VEC-66 spike [`template-hierarchy.md`](../spikes/template-hierarchy.md) §9 (the resource shapes); the first `/api/v1` resources, VEC-69, and `ConfigurationApiTest`, which pins every status below |

## Context

`vectis-server` had no `/api/v1` resource before VEC-69, so the first one sets the shape every later endpoint repeats: the write endpoints (VEC-93), authoring (VEC-71), boards and items (VEC-94) and the SPA client (VEC-42). An unratified draft had already set error shapes on its own. This record fixes them once, before a second endpoint copies the first by accident.

## Decision

1. **Errors are RFC 9457 `application/problem+json`.** The body has `type` (always `about:blank`: the status says all there is to say), `title` (the status phrase), `status`, `detail` (what is wrong, for a person) and, on a 422 only, `violations`: the resolver's typed list of `{field, level, message}`, verbatim. `field` is the dotted path, `level` is `key@version` or `workspace`, `message` is for the author. A client branches on `status`, never on `detail`.
2. **Status codes.** `400` the request is malformed (a bad path value, an unknown `include`); `404` a key names nothing, and a template the caller may not see is reported the same way; `409` the request conflicts with the current state; `412` an `If-Match` revision is stale; `422` the request is well formed and breaks the rules, with `violations`. Nothing else is a client error of this API.
3. **Keys, not ids, in paths.** A workspace is `/workspaces/{key}`, a template `/templates/{key}`, a template version `/templates/{key}/versions/{n}`. Ids stay inside the server.
4. **ETags are revisions.** A resource with a revision answers `ETag: "<revision>"`, and a write takes it back as `If-Match`; a stale one is a 412.
5. **One mapper.** The resolver's violations (`ConfigurationViolationException`), a missing template (`TemplateNotFoundException`) and a resource's own `ProblemException` become the body above in one class, `io.vectis.server.api.ProblemMapper`. A resource throws; it does not build an error response.

## Consequences

- Every endpoint reports a refused configuration the same way, with the level that carries each violation, so a client can show it next to the field it is about.
- A new status or a new member of the problem body is an amendment to this record.
- Until authentication (VEC-35) and tenancy (VEC-80, VEC-81) land, the read endpoints are tenant-agnostic and show built-in templates only.

## Alternatives considered

- **A bespoke `{error, message}` body.** Smaller, but every client and every agent then learns a private format, and RFC 9457 already says where extension members such as `violations` go.
- **`400` for rule violations.** One fewer code, but a malformed request and a refused configuration need different reactions: the first is a bug in the caller, the second is shown to a person.
- **Ids in paths.** Stable under rename, but the keys are immutable, already unique, and what the board, the importer and an agent already use.

## Reopen if

- A client needs machine-readable error kinds beyond the status and the violation `field`: add a `code` member rather than a new `type`.
- A resource cannot be addressed by one key.
