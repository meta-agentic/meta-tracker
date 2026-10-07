// Session-model spike probe: drives a real Chromium against the BFF prototype and a real
// identity provider, and records each acceptance criterion as pass/fail with its evidence.
//
//   NODE_EXTRA_CA_CERTS=../fixture/tls/ca.pem NODE_PATH=<dir with playwright> node session-probe.cjs
//
// Expects (see ../README.md): the provider on https://iam.vectis.localhost:9443 and three BFF
// replicas — A :8443 (defaults), B :8444 (bearer allow-list = another client), C :8445
// (bearer audience = a dedicated API audience).
"use strict";

const crypto = require("node:crypto");
const fs = require("node:fs");
const path = require("node:path");
const { chromium } = require("playwright");

const IAM = "https://iam.vectis.localhost:9443";
const A = "https://app.vectis.localhost:8443";
const B = "https://app.vectis.localhost:8444";
const C = "https://app.vectis.localhost:8445";
const CROSS = "https://app.other.localhost:8443";
const USER = { username: "alice", password: "spike-password-1" };
const CLIENT = { id: "vectis-bff", secret: "spike-client-secret-not-for-production" };
const XHR = { "X-Requested-With": "JavaScript" };

const results = [];
function record(id, criterion, pass, evidence) {
  results.push({ id, criterion, pass, evidence });
  console.log(`${pass ? "PASS" : "FAIL"}  ${id}  ${criterion}\n      ${JSON.stringify(evidence)}`);
}

async function signIn(page, origin, next) {
  await page.goto(origin + next);
  const landed = page.url();
  await page.fill("#username", USER.username);
  await page.fill("#password", USER.password);
  const started = Date.now();
  // Not `networkidle`: the shell holds an event stream open, so the network never idles.
  await Promise.all([
    page.waitForURL((u) => u.pathname === next || u.origin === IAM, { timeout: 15000 }).catch(() => {}),
    page.click("button[type=submit]")]);
  const ms = Date.now() - started;
  await page.waitForLoadState("domcontentloaded").catch(() => {});
  return { landed, final: page.url(), ms };
}

// --- a bearer token for the BFF's own client, obtained the way any OAuth client would -------
async function bearerToken() {
  const login = await fetch(IAM + "/login", {
    method: "POST", body: new URLSearchParams(USER), redirect: "manual",
  });
  const cookie = (login.headers.get("set-cookie") || "").split(";")[0];
  const verifier = crypto.randomBytes(32).toString("base64url");
  const challenge = crypto.createHash("sha256").update(verifier).digest("base64url");
  const redirectUri = A + "/auth/callback";
  const authorize = await fetch(IAM + "/authorize?" + new URLSearchParams({
    response_type: "code", client_id: CLIENT.id, redirect_uri: redirectUri, scope: "openid profile",
    state: "s", nonce: "n", code_challenge: challenge, code_challenge_method: "S256",
  }), { headers: { cookie }, redirect: "manual" });
  const code = new URL(authorize.headers.get("location")).searchParams.get("code");
  const token = await fetch(IAM + "/token", {
    method: "POST",
    headers: { authorization: "Basic " + Buffer.from(`${CLIENT.id}:${CLIENT.secret}`).toString("base64") },
    body: new URLSearchParams({ grant_type: "authorization_code", code, redirect_uri: redirectUri, code_verifier: verifier }),
  });
  return (await token.json()).access_token;
}

function jwtClaims(jwt) {
  return JSON.parse(Buffer.from(jwt.split(".")[1], "base64url").toString());
}

(async () => {
  const browser = await chromium.launch();
  // The spike's TLS chain is a local CA; certificate errors are ignored so the browser behaves
  // as it would against a trusted chain (Secure cookies, secure context).
  const newContext = () => browser.newContext({ ignoreHTTPSErrors: true });

  // ---------------------------------------------------------------- anonymous (AC2a, D7)
  {
    const ctx = await newContext();
    const page = await ctx.newPage();
    await page.goto(A + "/board/42");
    const u = new URL(page.url());
    record("P1", "anonymous deep link is sent to Vectis's sign-in page, not to the provider",
      u.origin === A && u.pathname === "/signin.html" && u.searchParams.get("next") === "/board/42", { url: page.url() });

    const api = await ctx.request.get(A + "/api/v1/me", { headers: XHR, maxRedirects: 0 });
    record("P2", "anonymous XHR to the API gets 401, not a redirect", api.status() === 401, { status: api.status() });

    const sse = await ctx.request.get(A + "/api/v1/stream", { headers: { accept: "text/event-stream" }, maxRedirects: 0 });
    record("P3", "anonymous event-stream subscribe gets 401", sse.status() === 401, { status: sse.status() });

    const stale = await ctx.request.get(A + "/api/v1/me", {
      headers: { ...XHR, cookie: "q_session=not-a-session" }, maxRedirects: 0 });
    record("P4", "XHR with an unusable session cookie gets a status, not a redirect (Quarkus answers 499)",
      stale.status() === 499, { status: stale.status() });
    await ctx.close();
  }

  // ------------------------------------------------------- same-site sign-in (AC2b, AC3a)
  const ctx = await newContext();
  const page = await ctx.newPage();
  const bodies = [];
  page.on("response", async (r) => {
    try {
      const type = r.headers()["content-type"] || "";
      if (/json|text|javascript|html/.test(type)) bodies.push({ url: r.url(), body: await r.text() });
    } catch { /* redirects and aborted streams have no body */ }
  });
  const flow = await signIn(page, A, "/board/42");
  record("P5", "same-site: sign-in completes the code flow and lands on the requested deep link",
    flow.final === A + "/board/42", { landed: flow.landed, final: flow.final, signInToBoardMs: flow.ms });
  await page.waitForFunction(() => Number(document.getElementById("events").textContent) >= 3, null, { timeout: 5000 }).catch(() => {});
  const shown = await page.evaluate(() => ({
    subject: document.getElementById("subject").textContent,
    tenant: document.getElementById("tenant").textContent,
    events: Number(document.getElementById("events").textContent),
  }));
  record("P6", "the SPA shell and an API call succeed on the session", shown.subject && shown.subject !== "…", shown);
  record("P7", "the event stream authenticates with the session cookie (EventSource)", shown.events >= 3, { events: shown.events });

  const cookies = await ctx.cookies();
  const visible = await page.evaluate(() => ({
    documentCookie: document.cookie,
    localStorage: Object.keys(localStorage).length,
    sessionStorage: Object.keys(sessionStorage).length,
  }));
  const appBodies = bodies.filter((b) => b.url.startsWith(A));
  const tokenInBody = appBodies.filter((b) => /eyJ[A-Za-z0-9_-]{10,}\.[A-Za-z0-9_-]{10,}\./.test(b.body)).map((b) => b.url);
  const session = cookies.filter((c) => c.name.startsWith("q_session"));
  record("P8", "no token is readable by script (storage, document.cookie, response bodies)",
    visible.localStorage === 0 && visible.sessionStorage === 0 && !/eyJ/.test(visible.documentCookie)
      && tokenInBody.length === 0 && session.length > 0 && session.every((c) => c.httpOnly && c.secure),
    { ...visible, appResponsesScanned: appBodies.length, tokenInBody,
      sessionCookies: session.map((c) => ({ name: c.name, bytes: c.value.length, httpOnly: c.httpOnly, secure: c.secure, sameSite: c.sameSite })) });
  const provider = cookies.find((c) => c.name === "tessera_session");
  record("P9", "the provider's session cookie is stored for its own host only (same-site)",
    !!provider && provider.domain === "iam.vectis.localhost" && provider.sameSite === "Strict",
    provider ? { domain: provider.domain, sameSite: provider.sameSite, httpOnly: provider.httpOnly } : null);

  // ------------------------------------------------------------------------ CSRF (D6)
  const noHeader = await page.evaluate(async () => (await fetch("/api/v1/items", { method: "POST" })).status);
  const withHeader = await page.evaluate(async () =>
    (await fetch("/api/v1/items", { method: "POST", headers: { "X-Requested-With": "JavaScript" } })).status);
  record("P10", "a state-changing call without the CSRF header is refused; with it, accepted",
    noHeader === 403 && withHeader === 201, { withoutHeader: noHeader, withHeader });

  // --------------------------------------------------------------- replicas (D9, AC multi)
  const onB = await ctx.request.get(B + "/api/v1/me", { headers: XHR });
  const meB = onB.status() === 200 ? await onB.json() : null;
  record("P11", "a session minted by replica A is accepted by replica B (shared key, no session store)",
    onB.status() === 200 && meB.subject === shown.subject, { status: onB.status(), me: meB });

  // ---------------------------------------------------------------------- bearer (D5)
  const token = await bearerToken();
  const claims = jwtClaims(token);
  const asBearer = async (origin, t) => {
    const r = await fetch(origin + "/api/v1/me", { headers: { authorization: "Bearer " + t } });
    return { status: r.status, body: r.status === 200 ? await r.json() : await r.text() };
  };
  const ok = await asBearer(A, token);
  record("P12", "a bearer token for an allow-listed client is accepted on the service path",
    ok.status === 200 && ok.body.path === "bearer",
    { status: ok.status, me: ok.body, tokenClaims: { aud: claims.aud, client_id: claims.client_id, realm_tenant: claims.realm_tenant ?? null } });
  const parts = token.split(".");
  const tampered = parts[0] + "." + parts[1] + "." + parts[2].slice(0, -4) + (parts[2].endsWith("AAAA") ? "BBBB" : "AAAA");
  const bad = await asBearer(A, tampered);
  record("P13", "a bearer token with a broken signature is refused", bad.status === 401, { status: bad.status });
  const otherClient = await asBearer(B, token);
  record("P14", "a valid token minted for a client not on the allow-list is refused",
    otherClient.status === 401, { status: otherClient.status, body: otherClient.body });
  const apiAudience = await asBearer(C, token);
  record("P15", "requiring a dedicated API audience refuses the provider's tokens (aud = issuer)",
    apiAudience.status === 401, { status: apiAudience.status, tokenAud: claims.aud });

  // ---------------------------------------------------------------------- logout (D8)
  await Promise.all([page.waitForURL((u) => u.pathname === "/signin.html", { timeout: 10000 }).catch(() => {}),
    page.click("#signout")]);
  const afterLogout = page.url();
  const sessionGone = (await ctx.cookies()).filter((c) => c.name.startsWith("q_session")).length === 0;
  const providerGone = !(await ctx.cookies()).some((c) => c.name === "tessera_session");
  await page.goBack().catch(() => {});
  await page.waitForLoadState("networkidle").catch(() => {});
  const backUrl = page.url();
  const apiAfter = await ctx.request.get(A + "/api/v1/me", { headers: XHR, maxRedirects: 0 });
  record("P16", "sign-out ends the Vectis and the provider session; back does not restore access",
    new URL(afterLogout).pathname === "/signin.html" && sessionGone && providerGone && apiAfter.status() === 401
      && !new URL(backUrl).pathname.startsWith("/board"),
    { afterLogout, vectisCookieCleared: sessionGone, providerCookieCleared: providerGone, afterBack: backUrl, apiAfterLogout: apiAfter.status() });
  const restart = await ctx.request.get(A + "/auth/start?next=/", { maxRedirects: 5, failOnStatusCode: false });
  record("P17", "after sign-out the provider refuses to issue a code without a fresh sign-in",
    restart.status() === 400, { status: restart.status(), url: restart.url().split("?")[0] });
  await ctx.close();

  // --------------------------------------------------------------- cross-site (AC3b, D4)
  {
    const x = await newContext();
    const xp = await x.newPage();
    let loginStatus = null;
    xp.on("response", (r) => { if (r.url() === IAM + "/login") loginStatus = r.status(); });
    let authorize = null;
    xp.on("response", (r) => { if (r.url().startsWith(IAM + "/authorize")) authorize = { status: r.status() }; });
    const cross = await signIn(xp, CROSS, "/board/7");
    const stored = (await x.cookies()).some((c) => c.name === "tessera_session");
    const body = await xp.textContent("body").catch(() => "");
    record("P18", "cross-site: /login succeeds but its SameSite=Strict cookie is not stored, so /authorize refuses",
      loginStatus === 204 && !stored && authorize && authorize.status === 400,
      { loginStatus, providerCookieStored: stored, authorize, final: cross.final.split("?")[0], body: (body || "").slice(0, 120) });
    await x.close();
  }

  // ------------------------------------------------- sign-in cost (measured, not a criterion)
  {
    const samples = [];
    for (let i = 0; i < 10; i++) {
      const s = await newContext();
      const sp = await s.newPage();
      const r = await signIn(sp, A, "/board/1");
      if (r.final === A + "/board/1") samples.push(r.ms);
      await s.close();
    }
    samples.sort((a, b) => a - b);
    const q = (p) => samples[Math.min(samples.length - 1, Math.floor(p * samples.length))];
    record("M1", "submit → deep link rendered, full chain (/login, /auth/start, /authorize, /callback, /token, restore, shell), 10 fresh browsers",
      samples.length === 10, { n: samples.length, p50ms: q(0.5), p90ms: q(0.9), maxMs: samples[samples.length - 1], samplesMs: samples });
  }

  await browser.close();
  const out = path.join(__dirname, "..", "results", "session-probe.json");
  fs.mkdirSync(path.dirname(out), { recursive: true });
  fs.writeFileSync(out, JSON.stringify({ ran: new Date().toISOString(), results }, null, 2) + "\n");
  const failed = results.filter((r) => !r.pass).length;
  console.log(`\n${results.length - failed}/${results.length} passed → ${out}`);
  process.exit(failed ? 1 : 0);
})().catch((e) => { console.error(e); process.exit(2); });
