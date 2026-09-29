# vec / wiki

_wiki stage of the memory-promotion pipeline. Notes land here as `.md` files._

The `ADR-VEC-*` series lives in [[vec/wiki/adr/_index|adr/]] — each space keeps its
own decision records beside its backlog.

## Contents (6)

- [[VEC-45]] — Template to instance model — inheritance and override semantics for workspace provisioning · IN PROGRESS
- [[VEC-50]] — JVM path filters are unanchored, so a future .sql or .java file under web/ would spend a Maven build and a Postgres service on an SPA-only change · IN REVIEW
- [[VEC-51]] — vectis-server has no deploy/k8s config for its Postgres dependency — VECTIS_DB_PASSWORD has no default · IN REVIEW
- [[VEC-55]] — SPA design pass — app shell, board cards, item detail and states · IN PROGRESS
- [[VEC-56]] — Public front page reflects reality — README status, ROADMAP and .vault snapshot · IN REVIEW
- [[VEC-57]] — Vault import, read side — BacklogSource contract, .vault parser and status mapping · IN PROGRESS

### Subfolders

- **adr/** — 1 note · see `adr/_index.md`
