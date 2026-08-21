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

  async function handleResponse(res) {
    let data = null;
    try {
      data = await res.json();
    } catch (e) {
      // No JSON body (e.g. a 204) — not an error.
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

  async function call(path, body) {
    const res = await fetch(path, {
      method: "POST",
      headers: {
        "Content-Type": "application/json",
        "X-Client-Id": clientIdFromUrl(),
      },
      body: JSON.stringify(body || {}),
    });
    return handleResponse(res);
  }

  // Ticket 014: admin-panel calls — authenticated with the real Bearer
  // access token saved at login (see saveSession below), not X-Client-Id.
  // The actual access decision (role check) happens server-side
  // (SecurityConfig + AdminIdentityProviderController); this only attaches
  // the credential the same way any other API client would.
  async function callAdmin(method, path, body) {
    const res = await fetch(path, {
      method,
      headers: {
        "Content-Type": "application/json",
        Authorization: "Bearer " + accessToken(),
      },
      body: body !== undefined ? JSON.stringify(body) : undefined,
    });
    return handleResponse(res);
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
  function saveSession(user, tokens) {
    sessionStorage.setItem("authcore.userId", user.id);
    sessionStorage.setItem("authcore.clientId", clientIdFromUrl());
    sessionStorage.setItem("authcore.email", user.email || "");
    sessionStorage.setItem("authcore.emailVerified", String(!!user.emailVerified));
    // Ticket 014: tokens is optional so any existing caller of
    // saveSession(user) alone keeps working unchanged.
    if (tokens && tokens.accessToken) {
      sessionStorage.setItem("authcore.accessToken", tokens.accessToken);
    }
  }

  function accessToken() {
    return sessionStorage.getItem("authcore.accessToken");
  }

  // Ticket 016: reads the tenant_id claim straight out of the stored JWT
  // payload — no signature verification, just a UI convenience to prefill
  // "which tenant" on admin pages (e.g. the metrics page defaults to the
  // caller's own tenant). The real access decision is still the server's
  // role/tenant check on the admin API; this can never grant anything by
  // itself. Returns null on any decode failure instead of throwing, so a
  // malformed/missing token just leaves the field blank.
  // Shared by currentTenantId()/currentRole() below. No signature
  // verification — this is a UI convenience (prefill a field, show/hide a
  // nav link), never an authorization decision; the server enforces that
  // for real on every admin API call regardless of what this reads.
  function decodeJwtPayload() {
    try {
      const token = accessToken();
      // JWTs are base64url, not plain base64 (- and _ instead of + and /,
      // and padding stripped) — atob() alone would silently mis-decode or
      // throw on real tokens.
      const base64 = token.split(".")[1].replace(/-/g, "+").replace(/_/g, "/");
      return JSON.parse(atob(base64));
    } catch (e) {
      return null;
    }
  }

  function currentTenantId() {
    const payload = decodeJwtPayload();
    return (payload && payload.tenant_id) || null;
  }

  // Ticket 020: which nav links the admin shell shows (e.g. "Clientes"
  // only for PLATFORM_ADMIN) — same "UI convenience, not an access
  // decision" caveat as currentTenantId() above.
  function currentRole() {
    const payload = decodeJwtPayload();
    return (payload && payload.role) || null;
  }

  // Ticket 020: the admin shell's "Cerrar sesión" — clears the local
  // session and sends the browser back to login. There's no server-side
  // session/token to revoke here (the access token just expires on its
  // own TTL, same as every other page in this app); this only clears
  // what THIS browser remembers.
  function logout() {
    sessionStorage.clear();
    window.location.href = "/ui/login?client_id=" + encodeURIComponent(clientIdFromUrl());
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

  // Ticket 014: same idea as requireSession(), but for admin-panel pages,
  // which need a real Bearer token rather than just a remembered userId.
  // Still only a client-side convenience to avoid showing a broken page —
  // the real access decision is the server's role check on the admin API,
  // which this page's own error handling surfaces as a normal 403.
  function requireAdminSession() {
    if (!accessToken()) {
      window.location.href = "/ui/login?client_id=" + encodeURIComponent(clientIdFromUrl());
      return false;
    }
    return true;
  }

  return {
    clientIdFromUrl,
    tokenFromUrl,
    call,
    callAdmin,
    showStatus,
    saveSession,
    accessToken,
    currentTenantId,
    currentRole,
    logout,
    currentUserId,
    currentSnapshot,
    requireSession,
    requireAdminSession,
  };
})();
