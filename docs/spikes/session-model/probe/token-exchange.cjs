// Reproduces findings F2 and F6: one Authorization Code + PKCE exchange for the BFF's client,
// printing the token endpoint's status and the fields of its response.
//
//   NODE_EXTRA_CA_CERTS=../fixture/tls/ca.pem node token-exchange.cjs "openid profile email"
//   NODE_EXTRA_CA_CERTS=../fixture/tls/ca.pem node token-exchange.cjs "openid profile email offline_access"
//
// F2: with the provider started with TESSERA_REQUIRE_SENDER_CONSTRAINT=true the exchange is a 400.
// F6: a refresh_token appears only with offline_access; expires_in is the access-token lifetime.
"use strict";

const crypto = require("node:crypto");

const IAM = "https://iam.vectis.localhost:9443";
const REDIRECT = "https://app.vectis.localhost:8443/auth/callback";
const scope = process.argv[2] || "openid profile email";

(async () => {
  const login = await fetch(IAM + "/login", {
    method: "POST", body: new URLSearchParams({ username: "alice", password: "spike-password-1" }),
  });
  const cookie = (login.headers.get("set-cookie") || "").split(";")[0];
  const verifier = crypto.randomBytes(32).toString("base64url");
  const challenge = crypto.createHash("sha256").update(verifier).digest("base64url");
  const authorize = await fetch(IAM + "/authorize?" + new URLSearchParams({
    response_type: "code", client_id: "vectis-bff", redirect_uri: REDIRECT, scope,
    state: "s", nonce: "n", code_challenge: challenge, code_challenge_method: "S256",
  }), { headers: { cookie }, redirect: "manual" });
  const code = new URL(authorize.headers.get("location")).searchParams.get("code");
  const token = await fetch(IAM + "/token", {
    method: "POST",
    headers: { authorization: "Basic " + Buffer.from("vectis-bff:spike-client-secret-not-for-production").toString("base64") },
    body: new URLSearchParams({ grant_type: "authorization_code", code, redirect_uri: REDIRECT, code_verifier: verifier }),
  });
  const body = await token.json();
  if (token.status !== 200) {
    console.log(JSON.stringify({ scope, status: token.status, body }));
    return;
  }
  const id = JSON.parse(Buffer.from(body.id_token.split(".")[1], "base64url").toString());
  console.log(JSON.stringify({
    scope, status: token.status, fields: Object.keys(body), expires_in: body.expires_in,
    idTokenLifetimeS: id.exp - id.iat, idTokenClaims: Object.keys(id),
  }));
})();
