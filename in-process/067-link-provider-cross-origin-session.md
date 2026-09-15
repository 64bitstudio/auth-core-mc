# 067 — `link-provider`: URL relativa + cookie de sesión perdida cross-origin

## Objetivo
Ticket retroactivo. Segundo hallazgo real durante la verificación en vivo de galgoth-studio#093 (Pantalla "Usuario", "Cuentas conectadas" → "Conectar"), distinto del de `X-Current-Refresh-Token` (ver el 066):

1. **URL relativa.** `AccountLinkProviderController.linkProvider` (ticket 063) devolvía `redirectUrl: "/oauth2/authorization/{registrationId}"` — una ruta relativa. Correcta para el único caller que existía hasta ahora (la UI propia de auth-core-mc, mismo origen), pero un caller cross-origin con `hosts_own_login_ui = true` (galgoth-studio, ticket 056) hace `window.location.href = redirectUrl` y termina navegando contra SU PROPIO origen (`studio-dev.../oauth2/authorization/...`, una ruta que no existe ahí) en vez de auth-core-mc. Verificado en vivo: el navegador aterrizó en una página en blanco.
2. **Cookie de sesión perdida.** `LinkIntentSession` (ticket 063) correlaciona "qué usuario pidió vincular" con una cookie de sesión de Spring (`JSESSIONID`). El endpoint que la fija (`POST /api/v1/account/link-provider/{provider}`) se llama cross-origin, y ni el fetch del frontend (sin `credentials: 'include'`) ni el CORS del backend (sin `Access-Control-Allow-Credentials`) permitían que el navegador la guardara — verificado con un `curl -i` real: la respuesta sí traía `Set-Cookie`, pero el navegador la descartaba en silencio. Sin esa cookie, al volver del consentimiento de Google/Facebook el backend no tiene forma de saber a qué usuario vincular la cuenta.

Ambos son el mismo patrón de fondo que el 066: ticket 063 diseñó correctamente para el único caller que existía en su momento (mismo origen), y galgoth-studio#093 es el primer caller real cross-origin de este mecanismo.

Decisión de alcance (VoBo explícito de Marco vía `AskUserQuestion`, dado que esto toca CORS credentials + `SameSite` de cookies — cambio de seguridad, no "de paso"): **opción "cookie cross-site" (`SameSite=None` + `credentials: 'include'` + `Access-Control-Allow-Credentials`)**, reutilizando el mecanismo de sesión ya existente en vez de rediseñar la correlación con un token nuevo.

## Criterios de aceptación (TDD)
- `AccountLinkProviderControllerTest`: `redirectUrl` es una URL absoluta (`app.base-url` + la ruta), no relativa.
- `CorsConfigTest`/`CorsConfigurationIntegrationTest`: `Access-Control-Allow-Credentials: true` para los orígenes del allowlist.
- `accountApi.spec.ts` (galgoth-studio): `linkProvider` llama con `credentials: 'include'`.
- Verificado en vivo contra dev: "Conectar" (Google) navega al origen correcto de auth-core-mc y, tras el consentimiento real, la cuenta queda vinculada al usuario correcto (no crea un usuario duplicado ni falla la correlación).

## Hecho
**Backend (auth-core-mc):**
- `AccountLinkProviderController.linkProvider`: `redirectUrl` ahora usa `app.base-url` como prefijo (mismo patrón ya establecido en `VerificationLinkFactory`, ticket 056).
- `CorsConfig`: `setAllowCredentials(true)` — sigue siendo un allowlist exacto de orígenes (nunca wildcard), solo cambia que esos orígenes ya confiables también pueden mandar/recibir cookies.
- `application-deploy.properties` (perfil `deploy`, **nunca** en local/tests — `http://localhost:8080` es el `redirect_uri` real registrado para pruebas locales de OAuth2, ver `docs/ARQUITECTURA.md`, y `SameSite=None` exige HTTPS real): `server.servlet.session.cookie.same-site=none` + `server.servlet.session.cookie.secure=true`.

**Frontend (galgoth-studio):**
- `accountApi.ts`: `linkProvider` ahora llama con `credentials: 'include'` — única función de este archivo que lo necesita (todas las demás son Bearer-only, sin estado de cookie).

**Tests nuevos/actualizados**, todos verdes localmente:
- `AccountLinkProviderControllerTest.linkProviderReturnsAnAbsoluteRedirectUrlForTheRealRegistrationId` (renombrado + assertion actualizada).
- `CorsConfigTest.credentialsAreAllowedForLinkProviderSSessionCookie`.
- `CorsConfigurationIntegrationTest.aRealPreflightGetsCredentialsAllowed`.
- `accountApi.spec.ts`: assertion `credentials: 'include'` agregada al test existente de `linkProvider`.

**Docs**: `docs/API.md` ampliado (bullet de CORS + sección de `link-provider`) con ambos hallazgos.

CI de Jenkins verde (ver PR). Verificado en vivo contra dev después del deploy, como parte de la verificación en vivo de galgoth-studio#093: "Conectar" con una cuenta de Google real completa el flujo y la cuenta queda vinculada al usuario correcto.

No rompe compatibilidad de contrato (mismo shape de `LinkProviderResponse`, solo cambia de relativa a absoluta) — sí es una ampliación real de la superficie de CORS credentials, señalada explícitamente y con VoBo antes de implementarse (ver `## Objetivo`).
