---
kind: story
space: vec
id: VEC-35
title: Client-Managed Federated OIDC Authentication & Secure Access Gatekeeper
status: TO DO
project: VEC
epic: VEC-7
storyPoints: 8.0
priority: P2
labels:
- Sprint-6
- VECTIS-023
- edition-community
- tier-Core-IAM
dependencies:
- VEC-32
---

## Description

As an Authorized Team Member, I want our single-page application to negotiate my login session and manage my security tokens directly with our identity provider, So that I can securely access my agile tracking boards without passing my corporate credentials through intermediary servers.

**Acceptance Criteria**

* The React SPA must independently initiate the OAuth2 Authorization Code Flow with PKCE against the resolved tenant authentication endpoint when an unauthenticated route is accessed.
* Upon successful callback redirect, the SPA must securely extract, hold, and manage the OIDC ID, Access, and Refresh tokens within app memory/state.
* The client application must automatically intercept outgoing API calls to the Quarkus backend, injecting the valid Access Token as a bearer header (Authorization: Bearer <token>).
* The SPA must monitor token expiration timers and seamlessly trigger token refresh handshakes with the AS in the background before active sessions drop.
* The backend API must validate incoming token cryptographic signatures and isolate data visibility entirely based on the tenant claims baked into the bearer payload.

**Developer Notes**
Frontend: Configure your React OIDC client layer (e.g., using oidc-client-ts) to act as a public client. Ensure the refresh token rotation strategy is handled entirely in-memory or using secure cookies if backed by a proxy. Backend: Strip any stateful session management or proxy-login logic out of the Quarkus application layer. Configure quarkus-oidc purely as a Resource Server (quarkus.oidc.application-type=service).

---

*Backlog ref: VECTIS-023 · Type: Story · Tier: Core IAM · Planned: Sprint 5 Security*
