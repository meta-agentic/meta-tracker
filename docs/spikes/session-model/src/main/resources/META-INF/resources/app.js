// The SPA never sees a token. Every call is same-origin and rides the session cookie; every
// call carries X-Requested-With: JavaScript (CSRF rule + "answer with a status, not a redirect").
"use strict";

const XHR = { "X-Requested-With": "JavaScript" };

function signIn() {
  location.assign("/signin.html?next=" + encodeURIComponent(location.pathname + location.search));
}

async function api(path, init = {}) {
  const response = await fetch(path, { ...init, headers: { ...XHR, ...(init.headers || {}) } });
  if (response.status === 401 || response.status === 499) {
    signIn();
    throw new Error("unauthenticated");
  }
  return response;
}

(async () => {
  const me = await (await api("/api/v1/me")).json();
  document.getElementById("subject").textContent = me.subject;
  document.getElementById("tenant").textContent = me["idToken.realm_tenant"] ?? "none";
  const events = new EventSource("/api/v1/stream");
  let count = 0;
  events.onmessage = () => { document.getElementById("events").textContent = String(++count); };
  events.onerror = () => { if (events.readyState === EventSource.CLOSED) signIn(); };
})();

document.getElementById("signout").addEventListener("click", async () => {
  await api("/auth/logout", { method: "POST" });
  const { issuer } = await (await fetch("/auth/signin-config")).json();
  await fetch(issuer + "/logout", { method: "POST", credentials: "include" });
  location.assign("/signin.html");
});
