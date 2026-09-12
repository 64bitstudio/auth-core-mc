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

Implementado exactamente como se describe arriba: `IdentityClient.ownUiOrigin()`,
`VerificationLinkFactory.build(client, hostedPath, token)`, y los tres
controllers (`EmailVerificationController`, `PasswordResetController`,
`EmailChangeController`) resolviendo vía `ClientContextResolver.resolveClient`
en vez de `resolveTenant`, plumbando el `IdentityClient` hasta
`EmailVerificationService`/`PasswordResetService`/`EmailChangeService`.
`PasswordResetService.requestReset` cambió su parámetro `Tenant` por
`IdentityClient` (deriva el tenant internamente vía `client.getTenant()`).

**Tests**: `IdentityClientTest` (+3, `ownUiOrigin()`), `VerificationLinkFactoryTest`
(reescrito, 3 casos: hosted/own-UI/own-UI-sin-redirect-uri-configurado), los
tres servicios y los tres controllers actualizados a la nueva firma. Suite
completa del proyecto en verde: **392 tests, 0 failures, 0 errors**.

**PR #104**, mergeado a `dev` tras 3 rondas de CI:
1. Build/tests verdes de entrada.
2. Quality Gate de SonarQube en ERROR — **4 hallazgos preexistentes en `dev`,
   sin relación con el diff de este ticket** (el "New Code" de este proyecto
   se mide desde una fecha fija, 29 de agosto, no contra el commit base de
   la rama — por eso aparecieron aquí). Reliability corregidos en código:
   `IdentifierFormat.EMAIL` (regex con backtracking cuadrático, java:S5852,
   reestructurado con grupos posesivos no ambiguos) y `Totp.generateSecret()`
   (creaba un `SecureRandom` nuevo por llamada, java:S2119, ahora reusa una
   instancia estática). Security Hotspots documentados en Javadoc y
   marcados "Safe" por Marco en el dashboard (mismo procedimiento del
   ticket 077): `SecurityConfig` CSRF disable (java:S4502, API stateless
   con Bearer JWT) y `Totp` HmacSHA1 (java:S4790, exigido por RFC 6238 para
   interoperar con Google Authenticator/Authy).
3. Verde tras el retrigger — merge fast-forward a `dev`, deploy automático
   confirmado (`auth-core-mc-dev-app-1` recreado, `/actuator/health` → 200).

**Verificación en vivo contra DEV real** (no simulada):
- `POST /api/v1/register` con `X-Client-Id: galgoth-studio` contra
  `https://auth-dev.64bitstudio.com` → `201`, usuario real creado
  (`marcocortes1234.mc@gmail.com`).
- `POST /api/v1/verify-email/request` con ese `userId` → primer intento
  `500` (`RESEND_API_KEY` nunca se había configurado en dev — hallazgo
  real de infraestructura, preexistente, no de este ticket; ya estaba
  documentado en `docs/ARQUITECTURA.md` línea 340 desde antes). Marco
  configuró la key real de Resend en `/home/ubuntu/secrets/auth-core-mc/.env.dev`
  vía SSH (nunca compartida en texto plano en el chat).
- Segundo intento → `500` de nuevo: `RESEND_FROM_ADDRESS` apuntaba a un
  placeholder `@example.com` sin verificar. **Segundo hallazgo real**: el
  dominio obvio para verificar (`mail.64bitstudio.com`) ya pertenece a
  `mail-core-mc` (su propio `docker-mailserver`, con DKIM/SPF/VERP ya
  configurados ahí) — usarlo también en Resend habría chocado con esos
  registros DNS. Decisión de Marco: verificar `mail.auth.64bitstudio.com`
  en Resend en su lugar (pendiente, ver "Pendiente" abajo). Para no bloquear
  esta verificación mientras tanto, se usó temporalmente el remitente
  sandbox de Resend (`onboarding@resend.dev`, funciona sin verificar
  dominio, solo hacia el email del dueño de la cuenta) — **dev queda con
  este remitente temporal hasta que `mail.auth.64bitstudio.com` esté
  verificado**.
- Tercer intento → `202`, logs limpios (sin excepciones). Correo real
  recibido y confirmado por Marco: el link es
  `https://studio-dev.galgoth.64bitstudio.com/verify-email/confirm?token=...`
  — **no** `https://auth-dev.64bitstudio.com/ui/verify-email/confirm`. Fix
  confirmado funcionando en producción real, no solo en tests unitarios.
- Estado en la BD real de dev confirmado por lectura directa:
  `identity_client.hosts_own_login_ui = true`,
  `redirect_uris = {https://studio-dev.galgoth.64bitstudio.com/auth/callback}`
  para `galgoth-studio` — el mecanismo del ticket 055 seguía intacto.

**Promoción**: `dev` → `qa` requiere que Marco corra el merge él mismo (el
clasificador de permisos bloquea a Claude modificando ramas compartidas
como `qa`/`prod`, igual que ya bloquea push directo a `dev` — ver
`main-branch-guard.sh`). `qa` → `prod` tiene además un gate manual en el
pipeline (`corePipeline.groovy`, `submitter: 'marco'`, sin excepción) —
nadie más que Marco puede aprobarlo, por diseño.

**Pendiente (fuera de alcance de este ticket, seguimiento aparte)**:
- Verificar `mail.auth.64bitstudio.com` en Resend (agregar el dominio,
  configurar los registros DNS que Resend entregue en Cloudflare, esperar
  verificación) y actualizar `RESEND_FROM_ADDRESS` a un remitente real bajo
  ese dominio en dev/qa/prod — hoy dev usa el sandbox `onboarding@resend.dev`
  como remitente temporal.
- Confirmar si `RESEND_API_KEY`/`RESEND_FROM_ADDRESS` de qa y prod están en
  el mismo estado (nunca configurados) — no verificado en este ticket, solo
  se tocó dev.
