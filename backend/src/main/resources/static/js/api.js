// auth-core-mc — shared client-side helper for the server-rendered UI (ticket 009).
//
// Every form on these pages submits via fetch() to the exact same JSON API
// documented in docs/API.md — this file has no parallel business logic, it
// only wires the browser's X-Client-Id header (a page navigation can't set
// a custom header itself, but the JS running on the page can) and a small
// client-side "session" so pages like /ui/cuenta don't have to ask a real
// person to type their own UUID (see docs/ARQUITECTURA.md ticket 009 for
// why this is a deliberate, temporary trust boundary, not a real
// server-enforced session).
const AuthCoreUi = (() => {
  function clientIdFromUrl() {
    return new URLSearchParams(window.location.search).get("client_id") || "";
  }

  function tokenFromUrl() {
    return new URLSearchParams(window.location.search).get("token") || "";
  }

  async function call(path, body) {
    const res = await fetch(path, {
      method: "POST",
      headers: {
        "Content-Type": "application/json",
        "X-Client-Id": clientIdFromUrl(),
      },
      body: JSON.stringify(body || {}),
    });
    let data = null;
    try {
      data = await res.json();
    } catch (e) {
      // No JSON body (e.g. a 204 from /token/revoke) — not an error.
    }
    if (!res.ok) {
      const message = (data && data.message) || `Error ${res.status}`;
      const err = new Error(message);
      err.code = data && data.error;
      err.status = res.status;
      throw err;
    }
    return data;
  }

  function showStatus(el, message, isError) {
    el.textContent = message;
    el.classList.remove("hidden", "success");
    el.setAttribute("role", isError ? "alert" : "status");
    if (!isError) {
      el.classList.add("success");
    }
  }

  // sessionStorage, not a cookie/real session: cleared when the tab closes,
  // never sent automatically to the server, and only ever read by this same
  // page's own JS — the point is convenience (don't ask a person to type
  // their own UUID) not authentication.
  function saveSession(user) {
    sessionStorage.setItem("authcore.userId", user.id);
    sessionStorage.setItem("authcore.clientId", clientIdFromUrl());
    sessionStorage.setItem("authcore.email", user.email || "");
    sessionStorage.setItem("authcore.emailVerified", String(!!user.emailVerified));
  }

  function currentUserId() {
    return sessionStorage.getItem("authcore.userId");
  }

  function currentSnapshot() {
    return {
      userId: sessionStorage.getItem("authcore.userId"),
      clientId: sessionStorage.getItem("authcore.clientId"),
      email: sessionStorage.getItem("authcore.email"),
      emailVerified: sessionStorage.getItem("authcore.emailVerified") === "true",
    };
  }

  // Client-side-only guard: if there's no session snapshot, send the
  // visitor back to /ui/login. This is NOT what protects the account API
  // endpoints themselves (those still require the real userId/token the
  // API expects) — it just avoids showing an empty/broken page.
  function requireSession() {
    if (!currentUserId()) {
      window.location.href = "/ui/login?client_id=" + encodeURIComponent(clientIdFromUrl());
      return false;
    }
    return true;
  }

  return {
    clientIdFromUrl,
    tokenFromUrl,
    call,
    showStatus,
    saveSession,
    currentUserId,
    currentSnapshot,
    requireSession,
  };
})();
