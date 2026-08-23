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

  // Ticket 014: shared by every call authenticated with the real Bearer
  // access token saved at login (see saveSession below), not X-Client-Id —
  // both the admin panel (callAdmin) and, since ticket 041, any other
  // /ui/cuenta action whose server-side endpoint requires a real
  // authenticated principal instead of the client-supplied-userId trust
  // boundary most /ui/cuenta actions still use (see SetPasswordController's
  // Javadoc for why "establecer contraseña" specifically needs this). The
  // actual access decision (role check, or "is this the token's own
  // account") always happens server-side; this only attaches the
  // credential the same way any other API client would.
  async function callAuthenticated(method, path, body) {
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

  // Ticket 014: admin-panel calls — kept as its own named entry point
  // (rather than renaming every existing call site to callAuthenticated)
  // since "this is an admin-panel call" is meaningful context at each of
  // its call sites; identical implementation, see callAuthenticated above.
  const callAdmin = callAuthenticated;

  function showStatus(el, message, isError) {
    // "is-loading" is cleared here too (not just by withBusy below) so the
    // two token-confirmation pages (verify-email, change-email) — which
    // show a spinner on page load, before any button/form exists to attach
    // withBusy to — clear it for free the moment their one showStatus()
    // call lands, success or error.
    el.classList.remove("hidden", "success", "error", "is-loading");
    el.setAttribute("role", isError ? "alert" : "status");
    el.classList.add(isError ? "error" : "success");

    // Ticket 032: icon-exito/icon-error next to the message. This file is a
    // shared, page-agnostic helper with no Thymeleaf access of its own — it
    // can't render fragments/icons.html directly, and the project's
    // established convention (see icons.html) is to never hardcode SVG
    // markup as a JS string literal. Instead, each of the pages that call
    // showStatus() renders both icons once, server-side, into an inert
    // <template id="status-icons"> (see e.g. login.html) — read here by id
    // and cloned per call. Kept as a same-signature DOM lookup rather than
    // a new parameter so none of this helper's ~20 existing call sites
    // needed to change. A page with no such template (currently only
    // admin-tenants.html, mid-edit on ticket 031's branch) just shows the
    // message with no icon — identical to this function's behavior before
    // this ticket.
    el.textContent = "";
    const iconsTemplate = document.getElementById("status-icons");
    if (iconsTemplate) {
      const iconName = isError ? "error" : "exito";
      const iconSource = iconsTemplate.content.querySelector('[data-status-icon="' + iconName + '"] svg');
      if (iconSource) {
        el.appendChild(iconSource.cloneNode(true));
      }
    }
    const textEl = document.createElement("span");
    textEl.textContent = message;
    el.appendChild(textEl);
  }

  // Ticket 023: shared "this trigger is mid-request" state — disables the
  // button and shows an inline spinner (see app.css's .is-loading) while
  // `task` runs, restoring it in a finally block so it recovers even if
  // `task` throws. Purely a UI affordance, same additive pattern as
  // currentRole()/logout() above — it doesn't change what any endpoint call
  // does or its contract.
  async function withBusy(button, task) {
    button.disabled = true;
    button.classList.add("is-loading");
    try {
      return await task();
    } finally {
      button.disabled = false;
      button.classList.remove("is-loading");
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
    // Ticket 041 (HU-5): whether this account has a password_hash yet —
    // drives whether /ui/cuenta offers "Establecer contraseña". Every
    // UserResponse (login/register/set-password) includes this field, so
    // it's always freshly written here; see currentSnapshot()'s
    // "!== 'false'" default for the one edge case (an already-open tab
    // from before this field existed) where the key could be missing.
    sessionStorage.setItem("authcore.hasPassword", String(!!user.hasPassword));
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
      // Defaults to "has a password" (true) unless explicitly "false" —
      // never "hide a card this session actually needs" on the one edge
      // case where the key predates ticket 041 (an already-open tab that
      // hasn't logged in again since this field started being saved).
      hasPassword: sessionStorage.getItem("authcore.hasPassword") !== "false",
    };
  }

  // Ticket 046: 5 minutes — must match LoginCompletionService.PENDING_2FA_TTL
  // (ticket 045). Purely a client-side UX nicety (proactively tell the user
  // their pendingToken is about to die instead of letting a submit fail
  // first) — the real expiration is still enforced server-side by Redis's
  // own TTL on the token, this can never make an already-expired token look
  // valid or vice versa.
  const PENDING_2FA_TTL_MS = 5 * 60 * 1000;

  // Ticket 046: shared "segundo factor pendiente" step for /ui/login
  // (password) and /ui/social-callback (social) — both call this the same
  // way once their own POST comes back 202 twoFactorRequired (ticket 045).
  // Wires the markup already rendered by fragments/two-factor-step.html
  // (server-side, th:replace'd into each page) rather than building any DOM
  // here — same "markup server-side, behavior in JS" split as every other
  // page in this file.
  //
  // `statusEl` is that page's own existing #status element, reused for
  // errors and the resend confirmation — no new status area introduced.
  // `onVerified(result)` receives the same `{ user, tokens }` shape a plain
  // 200 login/exchange already returns; each page decides what to do with
  // it (save the session, redirect) exactly like it already did before this
  // ticket for the no-2FA case.
  function startTwoFactorStep(pendingToken, method, statusEl, onVerified) {
    const section = document.getElementById("twofactor-step");
    const hint = document.getElementById("twofactor-hint");
    const form = document.getElementById("twofactor-form");
    const resendBtn = document.getElementById("twofactor-resend-btn");
    const expiryEl = document.getElementById("twofactor-expiry");

    section.classList.remove("hidden");
    hint.textContent =
      method === "TOTP"
        ? "Abre tu app autenticadora e ingresa el código actual."
        : "Te enviamos un código por correo o SMS — ingrésalo abajo.";

    // Ticket 046 (criterio de aceptación): reenvío solo tiene sentido para
    // OTP_EMAIL/OTP_SMS — TOTP no envía nada, el código ya vive en la app
    // autenticadora del usuario (mismo criterio que TwoFactorLoginController
    // usa server-side para el propio endpoint de reenvío).
    if (method === "OTP_EMAIL" || method === "OTP_SMS") {
      resendBtn.classList.remove("hidden");
    }

    let dead = false;
    // Ticket 046 (hallazgo real, encontrado probando en vivo, no solo
    // supuesto): TwoFactorLoginController consume el pendingToken ANTES de
    // validar el código (ticket 045) — cualquier intento fallido, sea por
    // un código incorrecto o por un rate-limit, ya invalidó el token para
    // siempre, exactamente igual que si hubiera expirado. Dejar el
    // formulario abierto invitaría a un segundo intento condenado a fallar,
    // esta vez con un error todavía más confuso ("invalid_token" en vez de
    // "wrong code"). Por eso invalidate() es el único camino de salida del
    // formulario, se llame por expiración real o por cualquier fallo de
    // /2fa-verify — no dos mecanismos paralelos para la misma consecuencia.
    function invalidate(message) {
      dead = true;
      clearTimeout(expiryTimer);
      form.querySelector("button[type=submit]").disabled = true;
      resendBtn.disabled = true;
      expiryEl.textContent = message;
      expiryEl.classList.remove("hidden");
    }

    const expiryTimer = setTimeout(() => invalidate("El código expiró. Vuelve a iniciar sesión."), PENDING_2FA_TTL_MS);

    form.addEventListener("submit", async (e) => {
      e.preventDefault();
      if (dead) return;
      try {
        await withBusy(e.submitter, async () => {
          const result = await call("/api/v1/login/2fa-verify", { pendingToken, code: e.target.code.value });
          clearTimeout(expiryTimer);
          onVerified(result);
        });
      } catch (err) {
        showStatus(statusEl, err.message, true);
        invalidate("Ese intento ya invalidó el código. Vuelve a iniciar sesión.");
      }
    });

    resendBtn.addEventListener("click", async () => {
      if (dead) return;
      try {
        await withBusy(resendBtn, async () => {
          await call("/api/v1/login/2fa-resend", { pendingToken });
          showStatus(statusEl, "Código reenviado.", false);
        });
      } catch (err) {
        showStatus(statusEl, err.message, true);
      }
    });
  }

  // Ticket 041 (HU-5): called right after a successful "Establecer
  // contraseña" so the card hides without requiring a full re-login.
  function markHasPassword() {
    sessionStorage.setItem("authcore.hasPassword", "true");
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
    callAuthenticated,
    showStatus,
    withBusy,
    saveSession,
    accessToken,
    currentTenantId,
    currentRole,
    logout,
    currentUserId,
    currentSnapshot,
    markHasPassword,
    requireSession,
    requireAdminSession,
    startTwoFactorStep,
  };
})();
