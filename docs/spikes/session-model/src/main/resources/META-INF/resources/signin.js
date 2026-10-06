// Posts the credentials to the provider's /login (form-encoded, credentials included). The
// provider answers 204 + its HttpOnly SameSite=Strict session cookie. Only then does the
// browser enter the code flow, through Vectis's /auth/start.
"use strict";

function safeNext() {
  const next = new URLSearchParams(location.search).get("next") || "/";
  return next.startsWith("/") && !next.startsWith("//") && !next.startsWith("/\\") ? next : "/";
}

document.getElementById("signin").addEventListener("submit", async (event) => {
  event.preventDefault();
  const error = document.getElementById("error");
  error.hidden = true;
  const { issuer } = await (await fetch("/auth/signin-config")).json();
  const body = new URLSearchParams({
    username: document.getElementById("username").value,
    password: document.getElementById("password").value,
  });
  const response = await fetch(issuer + "/login", { method: "POST", credentials: "include", body });
  if (response.status === 204) {
    location.assign("/auth/start?next=" + encodeURIComponent(safeNext()));
  } else {
    error.hidden = false;
  }
});
