# Architecture decision records

Decision records are named `ADR-VEC-NN-<slug>.md` and cited as `ADR-VEC-NN`. They are numbered consecutively in the order they are written; no number is reserved for a planned record, which gets the next free number when it is written. A decision usually starts as a spike document in [`docs/spikes/`](../spikes/) written to be lifted into its record.

| Number | Title | Status |
|---|---|---|
| [ADR-VEC-01](./ADR-VEC-01-product-requirements-and-features.md) | Product requirements & feature model | Proposed |
| [ADR-VEC-02](./ADR-VEC-02-identity-and-auth.md) | Identity & auth: the browser session model | Proposed |
| [ADR-VEC-03](./ADR-VEC-03-workflow-and-template-model.md) | Workflow and template model | Accepted (2026-10-07) |

Planned records, not yet written, so not yet numbered:

- Identity strategy (item IDs and primary keys); evidence in [`docs/spikes/time-ordered-pk/`](../spikes/time-ordered-pk/README.md).
- Real-time transport; drafted in [`docs/spikes/realtime-transport.md`](../spikes/realtime-transport.md).
- Client store & virtualization.
- Extension / plugin loading; findings in [`docs/spikes/plugin-loading.md`](../spikes/plugin-loading.md).
- Multi-tenant isolation & feature gating.
- Integration & MCP contract.
- Query language (open decision OD1).
- Reporting engine (open decision OD2).
