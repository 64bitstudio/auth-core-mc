# 056 — Redirect configurable para links de verificación/reset/cambio de email

## Objetivo
Mismo problema que resolvió el ticket 055 para el login social, pero para
los links que se envían por correo: hoy `VerificationLinkFactory` siempre
construye los links de verificación de cuenta, recuperación de contraseña
y cambio de email contra `app.base-url` (las páginas `/ui/**` hospedadas
por auth-core-mc), sin importar qué cliente originó la solicitud. Un
cliente con `hosts_own_login_ui=true` (como galgoth-studio, ticket 055)
recibe hoy links de auth-core-mc en sus correos en vez de links a su
propio dominio — inconsistente con el login social, que ya sí respeta esa
bandera.

Decisión de Marco (misma sesión, mismo patrón del ticket 055): **no se
agrega columna nueva** — se reutiliza `redirect_uris[0]` ya configurado
por cliente. Se extrae solo el **origen** (scheme+host+puerto) de ese
`redirect_uri` y se le concatena una ruta fija nueva por tipo de link,
igual a la ruta hospedada por auth-core-mc pero sin el prefijo `/ui`:

| Tipo de link | Ruta hospedada (auth-core-mc, sin cambios) | Ruta en el dominio del cliente (nueva) |
|---|---|---|
| Verificación de cuenta | `/ui/verify-email/confirm` | `/verify-email/confirm` |
| Recuperación de contraseña | `/ui/password-reset/confirm` | `/password-reset/confirm` |
| Cambio de email | `/ui/change-email/confirm` | `/change-email/confirm` |

Para un cliente sin `hosts_own_login_ui` (o sin `redirect_uris`), el
comportamiento no cambia: sigue usando `app.base-url` + la ruta hospedada,
igual que hoy.

## Alcance
- **Sí incluye:** `IdentityClient.ownUiOrigin()` (deriva el origen de
  `ownLoginUiRedirectUri()`); `VerificationLinkFactory.build(...)` acepta
  el `IdentityClient` resuelto y decide entre origen propio + ruta sin
  `/ui` vs. `app.base-url` + ruta hospedada; los tres controllers
  (`EmailVerificationController`, `PasswordResetController`,
  `EmailChangeController`) cambian de `resolveTenant` a `resolveClient` en
  su endpoint `/request` y plumban el `IdentityClient` hasta el servicio
  correspondiente.
- **No incluye:** las pantallas nuevas en galgoth-studio que consumirán
  esas rutas (`/verify-email/confirm`, `/password-reset/confirm`,
  `/change-email/confirm`) — eso son los tickets 079 y 080 de ese repo,
  que arrancan después de que este ticket esté en verde. Los endpoints
  `/confirm` de auth-core-mc (que no llevan `X-Client-Id`, el token solo
  ya identifica al usuario) no cambian.

## Criterios de aceptación (TDD)
- `IdentityClientTest`: `ownUiOrigin()` devuelve `Optional.empty()` si
  `hostsOwnLoginUi=false` o no hay `redirect_uris`; devuelve solo el
  origen (sin path) cuando sí aplica, incluso si el `redirect_uri`
  configurado trae un path largo (ej. `/auth/callback`).
- `VerificationLinkFactoryTest`: con un `IdentityClient` que no hostea UI
  propia, `build(...)` sigue devolviendo `app.base-url + hostedPath`
  exactamente como antes (no rompe el comportamiento para clientes
  existentes). Con uno que sí hostea UI propia, devuelve
  `origen-del-cliente + hostedPath-sin-"/ui" + "?token=..."`.
- Tests de `EmailVerificationService`, `PasswordResetService`,
  `EmailChangeService` actualizados a la nueva firma que recibe
  `IdentityClient`, verificando que el link enviado por email/SMS usa el
  dominio correcto en ambos casos (con y sin UI propia).
- Test de integración (`@SpringBootTest` o `MockMvc`) de al menos uno de
  los tres controllers confirma que ahora resuelve vía `resolveClient` (no
  `resolveTenant`) y que un cliente inactivo/tenant desactivado sigue
  bloqueado igual que antes (mismo comportamiento de
  `TenantDeactivatedException`, ticket 013).
- Verificación en vivo (dev, luego promovida a qa/prod igual que 054/055):
  disparar `/api/v1/verify-email/request` (o password-reset/change-email)
  con `X-Client-Id: galgoth-studio` contra el ambiente real y confirmar
  que el correo recibido apunta a
  `https://studio-dev.galgoth.64bitstudio.com/verify-email/confirm?token=...`
  y no a `https://auth-dev.64bitstudio.com/ui/verify-email/confirm`.

## Hecho
