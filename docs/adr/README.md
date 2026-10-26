# Architecture decision records

Naming, numbering and this index follow the [meta-os decision-record rule](https://github.com/meta-agentic/meta-os/blob/main/systems/repo-docs.md#decision-records), with project key `VEC`.

A decision usually starts as a spike document in [`docs/spikes/`](../spikes/) written to be lifted into its record.

| Number | Title | Status |
|---|---|---|
| [ADR-VEC-01](./ADR-VEC-01-product-requirements-and-features.md) | Product requirements & feature model | Accepted (2026-10-26) |
| [ADR-VEC-02](./ADR-VEC-02-identity-and-auth.md) | Identity & auth: the browser session model | Accepted (2026-10-26), amended |
| [ADR-VEC-03](./ADR-VEC-03-workflow-and-template-model.md) | Workflow and template model | Accepted (2026-10-07) |
| [ADR-VEC-04](./ADR-VEC-04-integration-contract-and-agent-surface.md) | Integration contract: backlog projection, write-back through the format's writer, agent surface | Accepted (2026-10-22) |

Planned records, not yet written:

- Identity strategy (item IDs and primary keys); evidence in [`docs/spikes/time-ordered-pk/`](../spikes/time-ordered-pk/README.md).
- Real-time transport; drafted in [`docs/spikes/realtime-transport.md`](../spikes/realtime-transport.md).
- Client store & virtualization.
- Extension / plugin loading; findings in [`docs/spikes/plugin-loading.md`](../spikes/plugin-loading.md).
- Multi-tenant isolation & feature gating; drafted in [`docs/spikes/tenant-isolation.md`](../spikes/tenant-isolation.md).
- Query language (open decision OD1).
- Reporting engine (open decision OD2).
