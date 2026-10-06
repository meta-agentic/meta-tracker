# Session-model spike prototype (VEC-77)

Throwaway code for [ADR-VEC-08](../../adr/ADR-VEC-08-identity-and-auth.md) (Proposed). It is not a
module of the root reactor and never builds with `mvn verify` at the repository root.

| Path | What it is |
|---|---|
| `src/` | A Quarkus 3.37.1 BFF: code-flow session for the browser, bearer for agents, the sign-in gate, CSRF and bearer-client guards, an SSE endpoint |
| `fixture/` | Starts the identity provider (built from source) over TLS with a throwaway Postgres; makes the local CA |
| `run-bff.sh` | Starts one BFF replica on a given HTTPS port |
| `probe/session-probe.cjs` | The browser probe: checks P1–P18 and measures M1 |
| `probe/token-exchange.cjs` | One code exchange, for findings F2 and F6 |
| `results/session-probe.json` | The recorded run the findings cite |

How to run: Appendix C of the findings document.
