# ADR-VEC-08 — Identity & auth: the browser session model

| | |
|---|---|
| **Status** | Proposed |
| **Type** | ADR from spike VEC-77 (Core IAM tier); the evidence is the prototype in [`docs/spikes/session-model/`](../spikes/session-model/) |
| **Sprint** | VEC-S5 |
| **Requirement** | [ADR-VEC-01](./ADR-VEC-01-product-requirements-and-features.md) **R-SEC-1** (multi-tenant OIDC with federated IdPs; client-managed Authorization Code + PKCE; the backend is a pure resource server) and **R-SEC-2** (tenant isolation fails closed) |
| **Supersedes** | The *mechanism* clause of R-SEC-1 — "client-managed … the backend is a pure resource server". The requirement's intent (OIDC, PKCE, federated IdPs, no credentials through Vectis's data path) stands. ADR-VEC-01's body is not edited. |
| **Deliverable** | This decision + a throwaway prototype in [`docs/spikes/session-model/`](../spikes/session-model/), a standalone Maven project outside the reactor, exercised by a browser probe against a real OIDC provider. No production code on `main`. |
| **Reading** | The ADR is Context, Decision, Rules and Consequences. Findings F1–F12 are the evidence; the appendices map the acceptance criteria, mark each claim measured or argued, and say how to reproduce. |
| **Unblocks** | VEC-35 (sign-in), VEC-81 (tenant binding). Constrains VEC-74 (stream auth), VEC-78 (one origin), VEC-80 (RLS input), VEC-32 (federation, now an amendment to this ADR). |

## Context

Vectis must be usable **only by signed-in users**: the application itself, not only its data.
R-SEC-1 as written describes a browser public client: the SPA runs the code flow itself, holds
ID, access and refresh tokens in script memory, and calls a backend that is a pure resource
server. That model gates the data but serves the application to anyone, leaves every token one
XSS away from exfiltration, and cannot authenticate the event stream, because `EventSource`
cannot send an `Authorization` header. The current IETF guidance for browser-based apps ranks a
backend-for-frontend (BFF) first for exactly these reasons. The product owner's direction
(2026-10-06) is a BFF.

The first identity provider is **Tessera**, an OIDC/OAuth 2.0 authorization server deployed as
its own public origin in single-tenant mode. Its behaviour, not OIDC in the abstract, decides
most of what follows, so the spike signs in against a real Tessera built from source rather than a
mock.

Nothing in Vectis authenticates today: `vectis-server` has one resource
(`ExtensionDiagnosticsResource`), no OIDC dependency, and the schema has no tenant column.

The question: **how does a browser reach Vectis only after signing in, with tokens never readable
by script, the tenant resolved fail-closed, and bearer access kept for agents and tools?**

## Decision

1. **D1 — Session model: a BFF in `vectis-server`, as two OIDC tenants.**
   - *Browser* (default tenant, `application-type=web-app`): the server is a **confidential
     client**, runs Authorization Code + PKCE (S256), and keeps the tokens in encrypted
     `HttpOnly; Secure` cookies. The browser never holds a token.
   - *Agents and tools* (`bearer` tenant, `application-type=service`): a request carrying
     `Authorization: Bearer` is validated as a resource-server request. A `TenantResolver`
     picks the tenant from the presence of that header.
   - Not one `hybrid` tenant: the ID token must carry `aud = client_id` and the provider's access
     tokens carry `aud = issuer` (F3); one tenant has one audience rule (F11).
2. **D2 — Tenant resolution: a Vectis-owned registry.** The verified `(iss, tenant claim)` maps
   to a Vectis tenant through a registry Vectis owns (VEC-81). Unknown, suspended or claim-less
   ⇒ `403`; a registry error ⇒ `503`; never a default tenant. Today's claim is the ID token's
   `realm_tenant` (F4). The tenant is bound once per request into a `TenantContext` and applied
   with `SET LOCAL` (VEC-14, VEC-80).
3. **D3 — Sign-in page: Vectis serves a minimal public `/signin`** that posts the credentials to
   the provider's `POST /login`, then sends the browser to Vectis's `/auth/start`, the one
   protected entry point that starts the code flow. A **gate** ahead of authentication sends a
   caller with no session there instead of to the provider, because the provider answers
   `/authorize` without its own session with `400`, not a redirect (F1). The page runs under a
   strict CSP: `script-src 'self'`, `connect-src 'self' <issuer>`, `frame-ancestors 'none'`, no
   inline script or style. It is retired when the provider ships its own login page.
4. **D4 — Domain plan: Vectis and the provider are same-site.** eTLD+1(Vectis) = eTLD+1(provider),
   both `https`. The provider's session cookie is `SameSite=Strict`; from a cross-site origin the
   `/login` call succeeds but the browser refuses to store the cookie, and the code flow then
   fails (F1, measured). Consequence: platform default hostnames on a public suffix (for example
   two `*.run.app` service URLs) are **cross-site to each other**. Both need custom domains under
   one registrable domain.
5. **D5 — Token validation (bearer path).** Local JWKS verification, never introspection on the
   hot path:
   - signature;
   - `iss` = the configured issuer;
   - `exp`/`nbf` with the Quarkus default skew;
   - `aud` = the issuer, **plus** `client_id` on an explicit allow-list (F3).
   
   The allow-list closes the hole that `aud = issuer` opens: without it, a token minted for
   *any* client of the provider is accepted. When the provider can mint a per-resource audience,
   `aud` becomes Vectis's own and the allow-list stays as defence in depth.
6. **D6 — CSRF.** Every SPA call carries `X-Requested-With: JavaScript`. A state-changing request
   authenticated by the session cookie without that header gets `403`. A cross-site page cannot
   add a custom header without a CORS preflight, and Vectis answers none. The session cookies are
   also `SameSite=Lax`, so a cross-site `POST` carries no session at all. Bearer calls carry no
   ambient credential and are exempt.
7. **D7 — XHR and SSE are never redirected.**
   - A call to `/api/**` (including the event stream) with no session cookie gets `401` from the
     gate.
   - A call with a session cookie that cannot be used gets `499` from Quarkus, because the
     header in D6 also switches off its redirect (F9).
   - The SPA treats `401` and `499` alike: it navigates to sign-in with `next` set to the
     current route.
   - The event stream rides the session cookie; when it closes for good, the SPA does the same.
8. **D8 — Session lifetime and sign-out.**
   - *Lifetime.* Request `offline_access`, so the provider issues a refresh token (F6), and
     refresh server-side (`token.refresh-expired=true`). Cap the Vectis session at an
     **absolute** lifetime no longer than the provider's login session (8 h by default), stamped
     at sign-in and checked per request. Quarkus has no absolute cap of its own.
   - *Expired session.* Set `authentication.session-expired-path` to the sign-in page, so an
     expired session that cannot be refreshed lands on Vectis's page, not on the provider's
     `400` (argued: the property exists in the pinned version but the spike did not exercise
     it).
   - *Sign-out* is two steps, because the provider advertises no `end_session_endpoint` (F7):
     1. Vectis ends its own session (`POST /auth/logout`).
     2. The page ends the provider session (`POST <issuer>/logout`, credentials included).
     
     VEC-35 adds a third step: revoke the refresh token at the provider's `/revoke`.
9. **D9 — Token state across replicas: encrypted cookies, no server-side store.** Every replica
   shares one encryption key from a Kubernetes Secret (≥ 32 characters), with split token cookies
   (`q_session`, `q_session_at`, `q_session_rt`). Rotating the key signs every user out once.
   That is accepted, since Quarkus holds one key.

## Rules

For VEC-35 and everything after it:

- **No token reaches the browser.** No endpoint returns a token; no token goes into
  `localStorage`, `sessionStorage` or a script-readable cookie. The browser journey in VEC-79
  asserts this on every run, the same way probe P8 does.
- **Public routes are an explicit, short list:**
  - the sign-in page and its static assets;
  - `/auth/signin-config`;
  - `/auth/callback`;
  - health probes.
  
  Everything else, the SPA shell included, needs a session or an allow-listed bearer. The gate
  and the HTTP permission policy must list the same public paths. In the prototype a path that
  was public in one and not the other broke sign-in.
- **`next` is a same-origin path, checked on both sides.** It must start with `/`, must not start
  with `//` or `/\`, and must contain no CR or LF. Otherwise it becomes `/`.
- **The tenant never comes from the caller.** Not from a header, a path or a body: only from the
  verified token through the registry (D2).
- **One encryption key per environment**, from a Secret, never the client secret's derived
  default.

## Consequences

- **R-SEC-1's mechanism is superseded**, its intent kept: PKCE, OIDC, and no credentials in
  Vectis's data path. The credentials do pass through Vectis's own sign-in *page* (D3), never
  through its server. Federated per-tenant IdPs (the rest of R-SEC-1) become a later amendment
  of this ADR (VEC-32), on top of the `bearer`/`default` split.
- **VEC-35 drops its original acceptance criteria** (tokens in script memory, a browser-side
  refresh loop) for the BFF set already filed on it. Its size falls from 8 to 5 because the
  unknowns moved here.
- **VEC-78 is a prerequisite**: the session cookie covers the SPA and the API only on one origin.
- **The SSE workaround is not needed**: VEC-74 authenticates its stream with the cookie, as P7
  shows.
- **The deployment must be same-site with the provider (D4).** That is an infrastructure
  decision, not an application setting.
- **Provider-side prerequisites, with how to check each:**

  | # | Need | Today | Check |
  |---|---|---|---|
  | U1 | `/authorize` without a session redirects to a configured login page, or returns `login_required` to the `redirect_uri` (OIDC Core §3.1.2.6) | `400` JSON at the provider (F1) | P17 / P18 turn into a redirect |
  | U2 | A confidential BFF behind a TLS-terminating edge gets tokens **without weakening every other client** | Only a deployment-wide opt-out (F2) | Per-client sender-constraint policy, or `private_key_jwt` + DPoP for the BFF |
  | U3 | Access tokens carry a per-resource `aud` (RFC 8707 resource indicators, or a per-client default) | `aud = issuer` always (F3) | P15 passes with the replica that requires `vectis-api` |
  | U4 | The access token carries the tenant claim | ID token only (F4) | `accessToken.realm_tenant` non-null in P12 |
  | U5 | `email` and `name` released under the `email`/`profile` scopes | Absent (F5) | `idToken.email` non-null in P11 |
  | U6 | A Vectis client registered beside the others, with exact redirect URIs | One bootstrap client per deployment | Two clients coexist |
  | U7 | A reachable non-prod instance on a same-site origin | None live | Staging sign-in |

  Until U2 is met, the provider deployment that serves Vectis runs with
  `TESSERA_REQUIRE_SENDER_CONSTRAINT=false`. That is a **deployment-wide** relaxation and
  must be an explicit decision of the provider's operator, not a side effect of adopting Vectis.
  Until U4 is met, the bearer path has no tenant: VEC-81 refuses bearer calls (`403`) rather than
  guess one.

## Findings

All measured findings come from one run of the probe ([`session-model/probe/session-probe.cjs`](../spikes/session-model/probe/session-probe.cjs),
results in [`session-model/results/session-probe.json`](../spikes/session-model/results/session-probe.json)):
Chromium 141 (Playwright 1.56.1), one Linux container, the prototype on Quarkus **3.37.1** (the
repo's pinned version), and Tessera built from source (Quarkus 3.39.3, profile
`prod,singlenode`) over TLS, with PostgreSQL 17 in Docker. **19/19 checks passed.**

### F1 · The provider serves no login page, and `/authorize` without a session is a dead end — measured

`/authorize` with no `tessera_session` cookie answers
`400 {"error":"access_denied","error_description":"no authenticated subject"}`, with no redirect
and no `login_required` sent back to the client (P17, P18).

A relying party that lets Quarkus start the code flow for an anonymous user strands the user on a
JSON error page. Hence the gate and `/auth/start` (D3).

Same-site sign-in works end to end (P5): the browser lands on the deep link it asked for. From a
cross-site origin, `/login` returns `204`, but the browser does not store the `SameSite=Strict`
cookie set on a cross-site response, and `/authorize` refuses (P18). The CORS allow-list in the
fixture includes the cross-site origin on purpose, so the only variable is the cookie.

### F2 · Confidential clients must be sender-constrained by default — measured

With the provider's default (`iam.authflow.require-sender-constraint=true`), the BFF's code
exchange is refused: `400 {"error":"invalid_request","error_description":"a client certificate is
required for a confidential client"}`. Public clients must present DPoP instead.

A BFF whose outbound calls cross a TLS-terminating edge cannot present a client certificate the
provider can see. The provider's only remedy today is the deployment-wide
`TESSERA_REQUIRE_SENDER_CONSTRAINT=false`, which the fixture sets. Because the BFF never lets a
token leave the server, sender-constraining buys it little. Turning it off for every confidential
client on the provider costs the others a lot. Hence U2.

This also weighs against the browser public client (option A): it would need DPoP key handling
in script.

### F3 · Every access token's `aud` is the issuer — measured

`aud = "https://iam.vectis.localhost:9443"` on the token (P12); the provider sets
`Set.of(issuer)` for every client. An audience check alone therefore admits any client's token:

- P14: a valid token for a client not on the allow-list is refused only because of the
  `client_id` check.
- P15: requiring a dedicated API audience refuses every provider token.

Hence D5 and U3.

### F4 · The tenant claim is on the ID token only — measured

The ID token carries `realm_tenant` and `realm_baseline` (scope `profile`); the access token
carries neither (`accessToken.realm_tenant: null` in P11 and P12).

The session path can resolve a tenant; the bearer path cannot. In single-tenant mode
`realm_tenant` is the same for every user of the deployment, so it identifies the provider realm,
not a Vectis tenant. That is why D2 maps it through a registry rather than trusting it as the
Vectis tenant id.

### F5 · No `email` or `name` in the ID token, even with the `email` scope — measured

`idToken.email: null` (P11), so the UI can show only the opaque subject. Hence U5.

### F6 · Refresh tokens only with `offline_access`; tokens live 300 s — measured

| Scope | Response fields |
|---|---|
| `openid profile email` | `access_token`, `id_token`; `expires_in` 300; the ID token also lives 300 s |
| `+ offline_access` | adds `refresh_token` |

Without a refresh token the Vectis session lasts 300 s plus `session-age-extension`, and renewal
needs a browser round trip through `/authorize`. That round trip is silent while the provider
session lives (8 h by default) and is a `400` after it (F1). Hence D8: refresh server-side, and
cap the session absolutely.

### F7 · No `end_session_endpoint`, `revocation_endpoint` or `userinfo_endpoint` in discovery — measured

Discovery lists only `authorization_endpoint`, `token_endpoint` and `jwks_uri`, so Quarkus cannot
perform RP-initiated logout. The two-step sign-out (D8) ends both sessions (P16). Afterwards the
provider issues no code without a fresh sign-in (P17), and going back in history lands on the
sign-in page with `/api` answering `401` (P16).

### F8 · Cookie budget — measured

| Cookie | Size | Contents |
|---|---|---|
| `q_session` | 869 B | encrypted ID token and session state |
| `q_session_at` | 945 B | encrypted access token |

Both are `HttpOnly; Secure; SameSite=Lax` (P8), each well under the 4 KiB cookie limit. EdDSA's
64-byte signatures help. The refresh-token cookie of D8 adds an encrypted opaque token (argued:
small).

### F9 · Quarkus answers `499` to XHR with an unusable session — measured

With `X-Requested-With: JavaScript` and a session cookie that cannot be decrypted or has expired,
Quarkus answers `499` instead of redirecting (P4). With no cookie at all, the gate answers `401`
(P2, P3). The SPA must treat both as "sign in again" (D7).

### F10 · The gate runs ahead of authentication — measured

In `quarkus-vertx-http` 3.37.1, `SecurityHandlerPriorities.AUTHENTICATION = 200`, installed as
route order −200. The gate is installed at `Integer.MIN_VALUE + 100`, after the body handler and
before CORS and authentication. P1 confirms the order: an anonymous deep link reaches
`/signin.html?next=…`, never the provider.

### F11 · Two OIDC tenants, not one `hybrid` tenant — measured

The browser path validates an ID token whose `aud` is the client id. The bearer path validates
access tokens whose `aud` is the issuer (F3). Both paths resolve on the same process, by header
(P11 `path: session`, P12 `path: bearer`).

### F12 · Sign-in cost — measured

From pressing *Sign in* to the deep link rendered, over the whole chain (`/login` with Argon2id,
`/auth/start`, `/authorize`, `/auth/callback`, `/token`, path restore, shell): **p50 323 ms,
p90 567 ms**, n = 10 fresh browsers, everything on one machine over loopback TLS (M1; an earlier
run gave 326 / 585 ms). This is a
floor; the WAN round trips of a real deployment add to it.

## Appendix A · Acceptance criteria — how each is met

| VEC-77 criterion | Met by |
|---|---|
| 1. The findings document answers D1–D9; every claim tagged measured or argued | Decision D1–D9 above; Appendix B |
| 2. Prototype proves: anonymous `GET /` ⇒ sign-in; after sign-in shell + API succeed with no token script-readable; XHR without session ⇒ `401`; wrong-`aud` bearer ⇒ `401` | P1; P5–P8; P2 (and P4 for `499`); P15 (P13 broken signature, P14 wrong client) |
| 3. Same-site shown both ways, the failure the one predicted | P5 + P9 (same-site), P18 (cross-site: cookie not stored ⇒ `/authorize` `400`) |
| 4. Provider-side prerequisites as checkable facts | Consequences, table U1–U7 |
| 5. `mvn -B -ntp verify` on `main` unaffected | The prototype is outside the reactor (its `pom.xml` has no parent and is not a module) |

## Appendix B · Evidence: measured or argued

| Claim | Kind | Evidence |
|---|---|---|
| Anonymous navigation → Vectis sign-in; API/stream → `401` | measured | P1, P2, P3 |
| Same-site sign-in completes and restores the deep link | measured | P5 |
| No token readable by script | measured | P8 (storage empty, `document.cookie` empty, 8 app responses scanned for JWTs, cookies `HttpOnly`) |
| SSE authenticates with the session cookie | measured | P7 |
| CSRF header rule | measured | P10 |
| Session readable by another replica (shared key) | measured | P11 |
| Bearer path: allow-listed client accepted; bad signature, other client, API audience refused | measured | P12–P15 |
| Two-step sign-out ends both sessions | measured | P16, P17 |
| Cross-site fails on the cookie | measured | P18 |
| Sender constraint refuses the BFF by default | measured | F2 (token endpoint response, default config) |
| Token lifetimes, refresh only with `offline_access` | measured | F6 (token responses) |
| Gate ordering | measured | F10 (constants read from the pinned jar) + P1 |
| Sign-in latency | measured | M1 |
| `SameSite=Lax` session cookies stop cross-site `POST` independently of the header | argued | browser cookie rules; not separately probed |
| `session-expired-path` lands an unrefreshable session on the sign-in page | argued | property present in 3.37.1; not exercised (token TTLs are not configurable in the provider) |
| Absolute session cap needs custom code | argued | no such setting in `OidcTenantConfig.Authentication` 3.37.1 |
| Public-suffix hostnames are cross-site to each other | argued | definition of *site*; follows from P18 |
| Key rotation signs everyone out once | argued | one `encryption-secret` per tenant config |

## Appendix C · How to run

Requires JDK 25, Maven, Docker, Node 22 with Playwright 1.56, and a Tessera checkout.

```bash
# 0. hosts (the JVM does not resolve *.localhost by itself)
echo '127.0.0.1 app.vectis.localhost iam.vectis.localhost app.other.localhost' | sudo tee -a /etc/hosts

# 1. the provider, built from source in single-node mode
(cd <tessera> && mvn -B -ntp -DskipTests -Dquarkus.package.jar.type=uber-jar \
   -Dquarkus.profile=prod,singlenode package -pl tessera-server -am)
TESSERA_JAR=<tessera>/tessera-server/target/tessera-server-0.1.0-SNAPSHOT-runner.jar \
  docs/spikes/session-model/fixture/start-tessera.sh &

# 2. the BFF prototype, three replicas
cd docs/spikes/session-model && mvn -B -ntp -DskipTests package
./run-bff.sh 8443 &                                       # A: defaults
./run-bff.sh 8444 VECTIS_BEARER_CLIENTS=vectis-agent &    # B: another bearer client allowed
./run-bff.sh 8445 VECTIS_BEARER_AUDIENCE=vectis-api &     # C: a dedicated API audience

# 3. the probe
cd probe && NODE_EXTRA_CA_CERTS=../fixture/tls/ca.pem node session-probe.cjs
```

F2 and F6 reproduce with [`probe/token-exchange.cjs`](../spikes/session-model/probe/token-exchange.cjs),
one code exchange for the BFF's client, printing the token endpoint's answer. For F2, start the
provider with `TESSERA_REQUIRE_SENDER_CONSTRAINT=true`. For F6, pass the scope with and without
`offline_access`.

## Appendix D · Prototype quality vs. what needs hardening

| In the prototype | In VEC-35 |
|---|---|
| Gate as a raw Vert.x route with a hard-coded public list | One public-path list shared by the gate and the permission policy, with a test that they agree |
| Local CA, test passwords and keys in scripts | Secrets from Kubernetes Secrets; the provider's real chain |
| Sign-in page with no rate-limit messaging or accessibility pass | Shared sign-in page with sibling UIs, or the provider's own once it exists |
| No refresh token (no `offline_access`), no absolute cap | D8 in full, including revoking the refresh token on sign-out |
| Bearer allow-list from one property | Allow-list per environment, plus the tenant refusal of VEC-81 for claim-less bearer tokens |
| `/board/*` shell fallback only | VEC-78's general history fallback and headers |

## Appendix E · Premises re-checked on today's `main`

- Quarkus is pinned at **3.37.1** in the root `pom.xml`; the prototype uses the same version.
- `vectis-server` has one JAX-RS resource and no `quarkus-oidc` dependency; the SPA's API client
  already defaults to same-origin `/api/v1` (`web/src/api/config.ts`).
- The native image (`deploy/docker/Dockerfile.native`) packages only the server application; the
  web client is not shipped (VEC-78).
