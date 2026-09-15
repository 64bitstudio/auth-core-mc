# API — auth-core-mc

> Endpoints del backend: rutas, métodos, qué reciben y qué responden. Explicado en simple. Se actualiza cuando cada ticket que añade o cambia endpoints se mueve a `/done`.

## Estado actual
`/register`, `/login`, `/verify-email/*`, `/change-email/*`, `/password-reset/*`, `/2fa/*`, `/identity-providers/*`, `/token/refresh`, `/token/revoke` y `/oauth2/**`/`/.well-known/openid-configuration` **implementados y probados** (tickets `002`-`007`, en `/done`). `/login` ahora emite tokens reales (ver más abajo). El flujo real de redirect+callback de login social (Google/Facebook, `docs/definiciones/login-social-real.md`) está **en construcción** — `/oauth2/authorization/**` y `/login/oauth2/code/**` resuelven tenant+proveedor por request (ticket `036`), el callback exitoso crea/vincula el `app_user` y emite un código de un solo uso (`SocialLoginSuccessHandler`/`FailureHandler`, ticket `037`), y ese código ya puede canjearse por tokens reales vía `POST /api/v1/oauth2/social-exchange` (ticket `038`, ver sección propia más abajo) — pero todavía no hay ningún botón/página en la UI que dispare el flujo completo end-to-end (ticket `039`).

**Ticket `045`:** `/login` y `/social-exchange` ahora exigen 2FA de verdad para el usuario que lo tiene activo, vía un gate compartido (`LoginCompletionService`) y un endpoint nuevo, `POST /api/v1/login/2fa-verify` — ver sección propia más abajo. **Ticket `046`:** `/ui/login` y `/ui/social-callback` ya interpretan la respuesta `202 twoFactorRequired` con un paso intermedio compartido — un usuario real con 2FA activo ya no queda varado. También agrega `POST /api/v1/login/2fa-resend` (reenvío de OTP) — ver secciones propias más abajo.

Este documento describe la superficie **JSON** (`/api/v1/**`, `/oauth2/**`). La UI web (ticket `009`, páginas `/ui/**` que llaman a estos mismos endpoints) está documentada en `docs/COMPONENTES.md`.

### ⚠️ `/identity-providers/*` requiere autenticación (a propósito, no es un descuido)
A diferencia de todos los demás endpoints de este documento, estos **no** están en la lista `permitAll` de `SecurityConfig`. Configurar credenciales OAuth de un tenant es una acción de administración real — dejarla abierta con el mismo modelo de confianza temporal que `/verify-email` o `/2fa` (solo `X-Client-Id`) sería un riesgo real, no uno acotado. Como no existe todavía autenticación de tenant-admin (llega con ticket `007` o uno nuevo), Spring Security la protege con su comportamiento por defecto: **401 para cualquiera**, fail-closed. Es una limitación intencional, no un bug — no la debilites sin agregar autenticación real primero.

### ⚠️ Límite temporal de confianza (hasta ticket 007)
`/verify-email/request` y `/change-email/request` reciben el `userId` directamente en el body — no hay todavía un token de acceso real que identifique "al usuario actual" (eso lo trae ticket `007`). Cualquiera que conozca (o adivine) un `userId` puede disparar el envío de un correo de verificación/cambio para ese usuario — molesto (spam, mitigado por el cooldown de 60s), pero no explotable: completar el flujo requiere poseer el token que llega a esa bandeja de entrada. Documentado también en `TenantScopedUserResolver.java`.

## Convenciones
- **Cómo se identifica el tenant en cada request**: header `X-Client-Id` con el `client_id` de un `IdentityClient` registrado (ver `BASE_DE_DATOS.md`). Si el header no corresponde a ningún cliente registrado, la respuesta es `401 unknown_client`. Esta fue la decisión pendiente que ticket `001` dejó abierta; ticket `002` la resolvió así — el flujo `/oauth2/authorize` de ticket `007` usará en cambio el parámetro estándar `client_id` de OAuth2, no este header (son superficies distintas: esta es la API "directa", esa es el flujo redirect).
- Todas las respuestas de error usan el mismo formato: `{ "error": "codigo_de_error", "message": "explicación" }`.
- **CORS (ticket `054`)**: `/api/v1/**` acepta llamadas cross-origin solo desde los orígenes listados en `CORS_ALLOWED_ORIGINS` (allowlist exacta por ambiente, separada por comas — vacío por defecto, ningún origen permitido). Nunca aplica a `/oauth2/**`/`/ui/**`: el JWKS lo consume el backend de cada cliente server-to-server, nunca un navegador. Una request con un `Origin` no listado no recibe `Access-Control-Allow-Origin` y, si además es preflight (o cualquier request real con ese header), Spring Security la rechaza con `403` — una request sin header `Origin` en absoluto (cualquier llamada no-navegador) nunca pasa por este chequeo. **Headers permitidos**: `Content-Type`, `X-Client-Id`, `Authorization`, `X-Current-Refresh-Token` (este último, ticket `093` de galgoth-studio — hallazgo real: faltaba, ver `CorsConfig`). **Credenciales (ticket `093` de galgoth-studio)**: `Access-Control-Allow-Credentials: true` para los orígenes del allowlist — necesario para que `POST /api/v1/account/link-provider/{provider}` (ver más abajo) funcione desde un caller cross-origin con `hosts_own_login_ui = true`: ese endpoint fija una cookie de sesión (`LinkIntentSession`) que el navegador solo guarda/reenvía si la respuesta trae esta cabecera y el caller pidió `credentials: 'include'`. En los 3 ambientes desplegados (nunca en local/tests) esa cookie también lleva `SameSite=None; Secure` (`server.servlet.session.cookie.*` en `application-deploy.properties`) — sin eso el navegador la descarta igual, con o sin CORS credentials.

## Registro y login (ticket `002`, `/login` actualizado en tickets `007` y `045`)
| Método | Ruta | Qué hace | Qué recibe | Qué responde |
|---|---|---|---|---|
| POST | `/api/v1/register` | Crea un usuario nuevo | Header `X-Client-Id`; body: `email` o `phone` (uno obligatorio), `password` (min. 8 caracteres, letra+dígito), `nombre`, `apellidos` | `201` + usuario creado (sin `password_hash`) |
| POST | `/api/v1/login` | Verifica credenciales y, si el cliente es first-party **y el usuario no tiene 2FA activo**, **emite tokens reales** (grant directo, sin redirect — ver ticket `007` en `ARQUITECTURA.md`). Si el usuario sí tiene 2FA activo, no emite tokens todavía (ver ticket `045` abajo) | Header `X-Client-Id`; body: `identifier` (email o phone), `password` | `200` + `{ "user": {...}, "tokens": { "accessToken", "refreshToken", "tokenType": "Bearer", "expiresInSeconds" } }`, o `202` + `{ "twoFactorRequired": true, "pendingToken", "method" }` si el usuario tiene 2FA activo |

`accessToken` es un JWT firmado (RS256) por el mismo `JwtGenerator` que usa `/oauth2/token`; `refreshToken` es un string opaco (no JWT), guardado hasheado (SHA-256) en la tabla `refresh_token` — ver `ARQUITECTURA.md` ticket `007` para el porqué de esta asimetría.

### `/login` con un cliente que no es first-party
Si el `IdentityClient` resuelto por `X-Client-Id` tiene `is_first_party = false`, `/login` responde `403 unauthorized_client` **antes** de verificar la contraseña — un cliente third-party debe usar `/oauth2/authorize` (Authorization Code + PKCE), nunca este atajo.

### Códigos de error de `/register` y `/login`
| HTTP | `error` | Cuándo |
|---|---|---|
| 400 | `weak_password` | Password no cumple la política mínima |
| 400 | `invalid_request` | Email/teléfono con formato inválido, o ninguno de los dos presente |
| 400 | `validation_error` | Falta `nombre`/`apellidos`/`password` en el body |
| 401 | `unknown_client` | El header `X-Client-Id` no corresponde a ningún cliente registrado |
| 401 | `invalid_credentials` | Login: identificador o password incorrectos (mensaje genérico a propósito, para no revelar cuál de los dos falló) |
| 403 | `unauthorized_client` | Login: el cliente resuelto por `X-Client-Id` no es first-party |
| 409 | `duplicate_identifier` | Registro: el email o teléfono ya existe para ese tenant |
| 429 | `too_many_attempts` | Más de 5 intentos de login fallidos en 15 minutos para ese tenant+identificador (Redis) |

## 2FA obligatorio en el login — password y social (ticket `045`)
Hallazgo del ticket `037`: hasta este ticket, ni `/api/v1/login` ni `/api/v1/oauth2/social-exchange` exigían el segundo factor de un usuario que lo tenía activo (`TwoFactorPreferenceService`, autoservicio desde `/ui/cuenta`, ticket `005`) — ambos emitían tokens de inmediato. Este ticket cierra ese hueco, **con un único mecanismo compartido** (`LoginCompletionService`) entre ambos flujos de entrada, no dos gates paralelos.

**⚠️ Cambio de contrato que rompe compatibilidad**, solo para usuarios con 2FA activo — aprobado explícitamente por el Product Owner: antes, esos usuarios recibían `200` + tokens de `/login`/`/social-exchange`; ahora reciben `202` + `TwoFactorRequiredResponse` y deben completar un paso adicional. Un usuario **sin** 2FA activo no ve ningún cambio en ninguno de los dos endpoints (criterio de aceptación explícito, cubierto con test).

| Método | Ruta | Qué hace | Qué recibe | Qué responde |
|---|---|---|---|---|
| POST | `/api/v1/login/2fa-verify` | Completa un login que quedó pendiente de 2FA (emitido por `/login` o por `/social-exchange`) y recién ahí emite tokens reales | Header `X-Client-Id`; body: `pendingToken`, `code` | `200` + el mismo `{ "user": {...}, "tokens": {...} }` de siempre, o `400 invalid_token` / `429 too_many_attempts` (ver abajo) |
| POST | `/api/v1/login/2fa-resend` | Ticket `046`. Reenvía el código OTP de un `pendingToken` todavía vigente (no-op para `TOTP`) | Header `X-Client-Id`; body: `pendingToken` | `202 Accepted`, o `400 invalid_token` / `429 too_many_attempts` (mismos criterios que `2fa-verify`, ver abajo) |

### El paso intermedio: `TwoFactorRequiredResponse`
Cuando `/login` o `/social-exchange` resuelven un usuario con 2FA activo, responden `202 Accepted` (no `200`, precisamente para que el status code por sí solo ya distinga los dos casos) con:
```json
{ "twoFactorRequired": true, "pendingToken": "…", "method": "TOTP" }
```
`method` es el mismo enum que usa `/2fa/method` (`OTP_EMAIL`\|`OTP_SMS`\|`TOTP`). `pendingToken` es de un solo uso real (vía `RedisTokenStore`, el mismo mecanismo que ya usa el código de canje social — ningún almacén nuevo), TTL 5 minutos, y empaqueta internamente el `clientId` + `userId` para que `/login/2fa-verify` pueda recuperarlos sin confiar en nada más que el cliente envíe.

**Método `OTP_EMAIL`/`OTP_SMS`:** el código ya se envía en este mismo paso (vía `OtpService.requestOtp`, reutilizado tal cual) — el cliente no necesita (ni puede, el `pendingToken` no lleva `userId`) llamar `/2fa/otp/request` por separado. Si hubiera una colisión con el cooldown de reenvío (un código muy reciente todavía válido), no se surface como error — el `pendingToken` se emite igual. **Método `TOTP`:** no hace falta ningún envío, el código ya vive en la app autenticadora del usuario.

### Códigos de error de `/api/v1/login/2fa-verify`
| HTTP | `error` | Cuándo |
|---|---|---|
| 400 | `invalid_token` | `pendingToken` inexistente/expirado/ya usado, el `X-Client-Id` no coincide con el cliente empaquetado en el `pendingToken`, el usuario ya no existe, o el `code` es incorrecto — deliberadamente el mismo error genérico para los cuatro casos, mismo criterio que `/social-exchange` |
| 429 | `too_many_attempts` | Más de 5 intentos de código incorrecto — para OTP vía `OtpService`, para TOTP vía `TotpService` (ticket `047`), ambos reutilizando `LoginRateLimiter`, cada método con su propio contador |
| 400 | `validation_error` | Falta `pendingToken`/`code` en el body |
| 401 | `unknown_client` | El header `X-Client-Id` no corresponde a ningún cliente registrado |

**Hallazgo de seguridad del ticket `045`, cerrado por el ticket `047`:** `TotpService.verify` ya reutiliza `LoginRateLimiter` (mismo mecanismo que `OtpService.verifyOtp`, namespace de intentos propio — `"totp:"` en vez de `"otp:"`) — más de 5 intentos de código incorrecto bloquea con `429 too_many_attempts`, igual que OTP. Aplica por igual en `/ui/cuenta` (autoservicio) y en este endpoint.

### `POST /api/v1/login/2fa-resend` (ticket `046`)
Reenvía el código OTP mientras un `pendingToken` (el mismo que emite `202 twoFactorRequired`) sigue vigente — pensado para la UI de `/ui/login`/`/ui/social-callback`, que necesita un botón "Reenviar código" sin obligar a un segundo login completo.

- Usa `RedisTokenStore.peek`, **no** `consume` — a diferencia de `2fa-verify`, reenviar un código nunca debe invalidar el `pendingToken` que el usuario sigue necesitando para el paso real de verificación.
- Mismo empaquetado `clientId::userId` y misma verificación cruzada de `X-Client-Id` que `2fa-verify` — mismo error genérico `invalid_token` si no coincide.
- Solo tiene efecto para `OTP_EMAIL`/`OTP_SMS` (llama a `OtpService.requestOtp`, reutilizado tal cual, incluyendo su cooldown real de reenvío de 30s — a diferencia del primer envío automático de `LoginCompletionService`, aquí una colisión de cooldown **sí** se surface como `429 too_many_attempts`, porque el usuario pidió este reenvío explícitamente). Para `TOTP` es un no-op silencioso (`202` igual, nada que reenviar — el código ya vive en la app autenticadora).

| HTTP | `error` | Cuándo |
|---|---|---|
| 400 | `invalid_token` | `pendingToken` inexistente/expirado/ya usado, o el `X-Client-Id` no coincide con el cliente empaquetado — mismo criterio que `2fa-verify` |
| 429 | `too_many_attempts` | Solo OTP: cooldown de reenvío de 30s todavía activo (`OtpService`, sin lógica nueva) |
| 400 | `validation_error` | Falta `pendingToken` en el body |

### Dónde se registra el "éxito" de un login con 2FA pendiente (`LoginEventRecorder`)
`LoginOutcome` es un `CHECK` de dos valores a nivel de base de datos (`SUCCESS`/`FAILURE`, `V5__login_event.sql`) — agregar un tercer estado ("pendiente de 2FA") es un cambio de esquema que necesita su propio VoBo dedicado, no algo para meter de paso en este ticket. Decisión tomada: `AuthController` sigue registrando `SUCCESS` en el mismo punto que antes (password verificado), sin esperar a que `LoginCompletionService` decida si hace falta 2FA — mismo precedente que ya existía en `SocialLoginSuccessHandler`, que registra éxito en "identidad probada", antes del paso de canje que puede fallar por separado. Consecuencia: un segundo factor incorrecto o abandonado **no genera ningún evento nuevo** — hallazgo real, no una decisión silenciosa: haría falta un tercer `LoginOutcome` para atribuir correctamente ese desenlace, lo cual es su propio ticket de cambio de esquema.

## Verificación de correo (ticket `003`)
| Método | Ruta | Qué recibe | Qué responde |
|---|---|---|---|
| POST | `/api/v1/verify-email/request` | Header `X-Client-Id`; body: `userId` | `202` (correo enviado) o `429 too_many_attempts` si se pidió hace menos de 60s |
| POST | `/api/v1/verify-email/confirm` | body: `token` (de la URL del correo) | `200` o `400 invalid_token` si expiró/no existe/ya se usó |

## Cambio de correo (ticket `003`)
| Método | Ruta | Qué recibe | Qué responde |
|---|---|---|---|
| POST | `/api/v1/change-email/request` | Header `X-Client-Id`; body: `userId`, `newEmail` | `202` (correo de confirmación enviado **al correo nuevo**, el actual sigue activo) o `409 duplicate_identifier` si `newEmail` ya existe en el tenant |
| POST | `/api/v1/change-email/confirm` | body: `token` | `200` (aplica el cambio y marca el correo nuevo como verificado) o `400 invalid_token` / `409 duplicate_identifier` (si alguien más tomó ese correo mientras el link estaba pendiente) |

## Recuperación de contraseña (ticket `004`)
| Método | Ruta | Qué recibe | Qué responde |
|---|---|---|---|
| POST | `/api/v1/password-reset/request` | Header `X-Client-Id`; body: `identifier` (email o teléfono) | **Siempre `202`**, exista o no ese identificador — ver advertencia abajo |
| POST | `/api/v1/password-reset/confirm` | body: `token`, `newPassword` | `200` o `400 invalid_token` / `400 weak_password` |

### ⚠️ `/password-reset/request` nunca revela si la cuenta existe
A diferencia de `/verify-email/request` (que sí puede responder `429` porque el llamador ya "posee" el `userId`), aquí el llamador solo aporta una adivinanza de email/teléfono — así que ni el código HTTP, ni el tiempo de respuesta ni el comportamiento pueden diferir entre "existe" y "no existe". El servicio nunca lanza una excepción distinguible para este caso; ver `PasswordResetService` en `docs/ARQUITECTURA.md`.

### A dónde apunta el link del correo (ticket `056`)
Mismo mecanismo del ticket `055`, ahora también para estos tres links (no solo el redirect del login social): si `identity_client.hosts_own_login_ui = false` (default, todo cliente anterior al ticket 056), el link va a la página hospedada por auth-core-mc (`app.base-url` + `/ui/verify-email/confirm` / `/ui/change-email/confirm` / `/ui/password-reset/confirm`), igual que siempre. Si `hosts_own_login_ui = true` (ej. `galgoth-studio`), el link va al **origen** de `identity_client.redirect_uris[0]` (mismo campo que ya usa el login social) con la misma ruta **sin el prefijo `/ui`** — `/verify-email/confirm`, `/change-email/confirm`, `/password-reset/confirm` en el dominio propio del cliente, que el frontend de ese cliente debe implementar (`VerificationLinkFactory.build(...)`, `IdentityClient.ownUiOrigin()`). El `?token=...` es idéntico en ambos casos y los endpoints `/confirm` (que no llevan `X-Client-Id`) no cambian.

## 2FA (ticket `005`)
Mismo header `X-Client-Id` + `userId` en el body que el resto de endpoints "temporales" (ver advertencia arriba, aplica igual aquí).

| Método | Ruta | Qué recibe | Qué responde |
|---|---|---|---|
| POST | `/api/v1/2fa/otp/request` | `userId` | `202` o `429 too_many_attempts` (cooldown de 30s) |
| POST | `/api/v1/2fa/otp/verify` | `userId`, `code` | `200` o `400 invalid_token` (código incorrecto/expirado/ya usado) o `429` (más de 5 intentos fallidos) |
| POST | `/api/v1/2fa/totp/enroll` | `userId` | `200` + `{ "secret": "..." }` — mostrar una sola vez como QR/código manual, nunca se vuelve a exponer en claro |
| POST | `/api/v1/2fa/totp/verify` | `userId`, `code` | `200` o `400 invalid_token` (incluye el caso "este código ya se usó") |
| POST | `/api/v1/2fa/method` | `userId`, `method` (`NONE`\|`OTP_EMAIL`\|`OTP_SMS`\|`TOTP`) | `200` o `400 totp_not_enrolled` si se intenta activar `TOTP` sin haber hecho `enroll` antes |

## Establecer contraseña de una cuenta social-only (ticket `041`, HU-5)
A diferencia de `/2fa` y `/change-email` de arriba, **este endpoint sí requiere un Bearer access token real** (header `Authorization: Bearer <accessToken>`, el mismo que emite `/api/v1/login` o, cuando el resto de la épica de login social esté mergeada, el intercambio social) — no el header `X-Client-Id` ni un `userId` en el body. La razón: completar `/2fa`/`/change-email` con solo un `userId` adivinado sigue exigiendo poseer el correo/SMS de la víctima; establecer una password no tiene ese segundo factor — surte efecto de inmediato y permitiría iniciar sesión como esa cuenta en el acto. El `userId` se toma del claim `sub` del JWT verificado, nunca del body.

| Método | Ruta | Qué recibe | Qué responde |
|---|---|---|---|
| POST | `/api/v1/account/password` | Header `Authorization: Bearer <accessToken>`; body: `newPassword` (misma política que `/register`: mín. 8 caracteres, letra+dígito) | `200` + el usuario actualizado (`hasPassword: true`), o `409 password_already_set` si la cuenta ya tenía una password (nunca se sobreescribe), o `400 weak_password`, o `401` sin un Bearer token válido |

`UserResponse` (el mismo objeto que devuelven `/register`, `/login` y este endpoint) incluye desde este ticket el campo `hasPassword` (booleano, derivado de `password_hash != null`) — es lo que `/ui/cuenta` usa para decidir si ofrece esta acción.

## Cambiar contraseña, autenticado (ticket `061`, "Mi Perfil" de galgoth-studio)
Tercer caso junto a "establecer" (arriba, cuenta social-only sin password) y "olvidé mi contraseña" (`/password-reset`, sin sesión): un usuario CON contraseña que la cambia confirmando la actual. Mismo criterio de auth (Bearer real, `userId` del `sub`).

| Método | Ruta | Qué recibe | Qué responde |
|---|---|---|---|
| PATCH | `/api/v1/account/password` | Header `Authorization: Bearer <accessToken>`; body: `currentPassword`, `newPassword` (misma política que `/register`) | `200` + el usuario actualizado, o `401 incorrect_current_password` si `currentPassword` no coincide, o `409 no_password_set` si la cuenta es social-only (usar `POST` de arriba en su lugar), o `400 weak_password`, o `401` sin un Bearer token válido |

Cambiar la contraseña **no** revoca las demás sesiones automáticamente
— son dos acciones independientes, cada una explícita (ver ticket `062`,
"cerrar sesión en todos los dispositivos").

## Perfil de cuenta: leer/editar información personal (ticket `060`, "Mi Perfil" de galgoth-studio)
Mismo criterio de autenticación que `/api/v1/account/password` (Bearer real, `userId` del `sub` del JWT, nunca del body ni de `X-Client-Id`).

| Método | Ruta | Qué recibe | Qué responde |
|---|---|---|---|
| GET | `/api/v1/account/profile` | Header `Authorization: Bearer <accessToken>` | `200` + `UserResponse` (incluye `country`/`username`, `null` si no están configurados) |
| PATCH | `/api/v1/account/profile` | Header `Authorization: Bearer <accessToken>`; body: `nombre`, `apellidos` (obligatorios), `country`, `username` (opcionales, `null`/vacío los borra) | `200` + `UserResponse` actualizado, o `409 duplicate_identifier` si `username` ya está tomado por otro usuario del mismo tenant (la unicidad es por tenant — el mismo `username` en tenants distintos no choca), o `401` sin un Bearer token válido |

El correo **no** se edita acá — sigue el flujo de 2 pasos ya existente
(`/api/v1/change-email/request` + `/confirm`, ver arriba). `UserResponse`
gana los campos `country`/`username` desde este ticket, y `createdAt`
desde el ticket `065` ("Miembro desde", pantalla Usuario de
galgoth-studio, ticket `093`).

## Sesiones activas (ticket `062`, "Mi Perfil" de galgoth-studio)
`refresh_token` gana `user_agent`/`created_at`/`last_used_at` — capturados
en `POST /api/v1/login`, `/api/v1/oauth2/social-exchange` y
`/api/v1/login/2fa-verify` (los 3 puntos reales donde se emite un refresh
token), y actualizados (`last_used_at`) en cada
`POST /api/v1/token/refresh` exitoso. Sin geolocalización de IP (decisión
de Marco) — solo navegador/sistema operativo, parseados del `User-Agent`
crudo en el momento de leer.

"Actual" (cuál sesión es la que hace la request) se determina mandando el
refresh token crudo que el frontend ya tiene guardado en el header
opcional `X-Current-Refresh-Token` — el JWT de acceso no lleva ninguna
referencia a qué refresh token lo emitió, así que sin este header ninguna
fila se marca como actual (la lista sigue funcionando igual).

| Método | Ruta | Qué recibe | Qué responde |
|---|---|---|---|
| GET | `/api/v1/account/sessions` | Header `Authorization: Bearer <accessToken>`; opcional `X-Current-Refresh-Token: <refreshToken>` | `200` + lista de `SessionSummary` (id, browser, os, createdAt, lastUsedAt, current), más recientes primero. Solo sesiones no revocadas y no expiradas. |
| DELETE | `/api/v1/account/sessions/{id}` | Header `Authorization: Bearer <accessToken>` | `204`, o `404 session_not_found` si no existe o no pertenece al usuario autenticado (nunca se distingue cuál de las dos) |
| POST | `/api/v1/account/sessions/revoke-others` | Header `Authorization: Bearer <accessToken>`; opcional `X-Current-Refresh-Token: <refreshToken>` | `204` — revoca todas las sesiones del usuario excepto la que coincide con el header (sin el header, revoca todas) |

## Vincular una cuenta social nueva desde el perfil (ticket `063`, "Mi Perfil" de galgoth-studio)
A diferencia del login social (ticket 037+), acá el usuario YA tiene una
sesión válida — solo Google/Facebook están soportados (Apple queda fuera
de alcance, `400 unsupported_provider`). El `client_id` se toma del claim
`aud` del propio JWT, no de un header aparte.

| Método | Ruta | Qué recibe | Qué responde |
|---|---|---|---|
| GET | `/api/v1/account/connected-providers` | Header `Authorization: Bearer <accessToken>` | `200` + lista de `ConnectedProviderSummary` (`provider`, `linked`) para Google y Facebook, reflejando `external_identity` real |
| POST | `/api/v1/account/link-provider/{provider}` | Header `Authorization: Bearer <accessToken>`; `{provider}` = `google` o `facebook` | `200` + `{redirectUrl}` — el FRONTEND navega el navegador completo a esa URL (mismo mecanismo `/oauth2/authorization/{registrationId}` que ya usa el login social), o `400 unsupported_provider` |

**Hallazgos reales de la verificación en vivo (ticket `093` de galgoth-studio)**: `redirectUrl` es siempre una URL **absoluta** (`app.base-url` + la ruta), nunca relativa — una ruta relativa se resuelve mal desde un caller cross-origin (`window.location.href` navega contra el origen del CALLER, no el de auth-core-mc). Y el caller debe llamar este endpoint con `credentials: 'include'` (ver bullet de CORS arriba) — el backend fija una cookie de sesión (`LinkIntentSession`) para saber, al volver del consentimiento del proveedor, qué usuario pidió vincular; sin `credentials: 'include'` el navegador la descarta y el vínculo no puede completarse.

Al volver de Google/Facebook, el resultado aterriza en
`{origin propio del cliente}/usuario` con `?linked={provider}` (éxito) o
`?link_error={no_email|already_linked|user_not_found}` — nunca emite
tokens nuevos (la sesión ya era válida desde antes de salir al
proveedor). `already_linked` significa que esa cuenta social ya
pertenece a OTRO usuario del mismo tenant — nunca se reasigna ni se
desvincula del original.

## Eliminar cuenta (ticket `064`, "Mi Perfil" de galgoth-studio)
`app_user` gana `deactivated_at` (`V14`, nullable, `NULL` = activo) — mismo
patrón que `tenant.deactivated_at` (ticket 013). A diferencia de `Tenant`,
sin purga física automática (fuera de alcance de este ticket).

Orden estricto, en este orden exacto: (1) valida `confirmIdentifier`
contra el email/teléfono real del usuario -- nunca por un clic accidental;
(2) llama SÍNCRONAMENTE `POST {GALGOTH_STUDIO_INTERNAL_URL}/api/internal/users/{userId}/purge-projects`
(mismo secreto compartido `X-Internal-Secret`/`GALGOTH_INTERNAL_SECRET`
que galgoth-studio ya definió en su propio ticket 091 -- coordinado a
mano, mismo valor por ambiente en ambos repos) -- si esta llamada falla
(no configurado, red, timeout, respuesta no-2xx), la cuenta NUNCA queda
desactivada (decisión explícita de Marco: nunca dejar proyectos públicos
huérfanos visibles en Explorar); (3) solo si la purga tuvo éxito:
`User.deactivate()` + revoca TODOS los refresh tokens del usuario
(reutiliza `AccountSessionsService.revokeOthers(userId, null)`, ticket
062 -- mismo mecanismo que "Cerrar sesión en todos los dispositivos",
sin duplicar esa lógica).

**Rechazo de tokens nuevos para una cuenta desactivada** -- único punto
real de decisión: `DirectTokenService.doIssueTokens`, el método interno
compartido por TODO camino que emite un token nuevo (login directo,
login social, verificación de 2FA) -- mismo criterio de "un solo punto
de decisión" que `ClientContextResolver` ya usa para tenants
desactivados. `POST /api/v1/token/refresh` no repite este chequeo a
propósito: ya queda cubierto por la revocación del paso (3) de arriba
(un refresh token revocado ya falla con `invalid_token`).

| Método | Ruta | Qué recibe | Qué responde |
|---|---|---|---|
| DELETE | `/api/v1/account` | Header `Authorization: Bearer <accessToken>`; body `{confirmIdentifier}` | `204` en éxito. `400 confirmation_mismatch` si `confirmIdentifier` no coincide con el email/teléfono real (nada cambia). `500 account_deletion_failed` si la purga de galgoth-studio falla o la integración no está configurada (nada cambia). |
| POST | `/api/v1/login` (y `/social-exchange`, `/login/2fa-verify`) | — | `403 user_deactivated` si el usuario ya eliminó su cuenta (mismo criterio que `403 tenant_deactivated`) |

## Configuración de login social por tenant (ticket `006`)
Requiere autenticación (ver advertencia arriba). Header `X-Client-Id` (no un `tenantId` en la ruta — el tenant siempre es el que resuelve el header, así un cliente nunca puede tocar la configuración de otro).

| Método | Ruta | Qué recibe | Qué responde |
|---|---|---|---|
| GET | `/api/v1/identity-providers` | — | `200` + lista de proveedores configurados (**sin** `client_secret`, ni siquiera cifrado) |
| PUT | `/api/v1/identity-providers/{provider}` | `provider` = `GOOGLE`\|`FACEBOOK`\|`APPLE`; body: `clientId`, `clientSecret` | `200` + la vista sin secreto, o `400 unsupported_provider` para `APPLE` (ver nota abajo) |
| DELETE | `/api/v1/identity-providers/{provider}` | — | `204` (deshabilita; no falla si nunca se configuró) |

### Apple Sign In sigue bloqueado
`PUT /identity-providers/APPLE` responde `400 unsupported_provider` siempre — Apple no usa un par `client_id`/`client_secret` como Google/Facebook, necesita una clave privada + Team ID + Key ID de una membresía **paga** de Apple Developer Program ($99/año), pendiente de que confirmes si la quieres.

## Refresh y revocación del token directo (ticket `007`)
Distinto de `/oauth2/token` y `/oauth2/revoke` (abajo) — estos dos operan sobre el `refreshToken` opaco emitido por `/api/v1/login`, no sobre el flujo Authorization Code.

| Método | Ruta | Qué recibe | Qué responde |
|---|---|---|---|
| POST | `/api/v1/token/refresh` | Header `X-Client-Id`; body: `refreshToken` | `200` + un `TokenPair` nuevo (nuevo `accessToken`; el mismo `refreshToken` — no se rota, ver `ARQUITECTURA.md`) o `400 invalid_token` si es inválido/expiró/fue revocado |
| POST | `/api/v1/token/revoke` | Header `X-Client-Id`; body: `refreshToken` | `204` siempre (idempotente: revocar un token ya revocado o inexistente no es un error) |

## OAuth2 / OIDC (ticket `007`)
Flujo estándar para clientes third-party (o first-party que prefieran no manejar el password directamente). Verificado en vivo: `/.well-known/openid-configuration` y `/oauth2/jwks` responden metadata OIDC/JWKS reales.

| Método | Ruta | Qué hace |
|---|---|---|
| GET | `/oauth2/authorize` | Inicio del flujo Authorization Code + PKCE (estándar Spring Authorization Server). ⚠️ Redirige a un formulario de login por defecto de Spring — este proyecto no tiene todavía una UI propia detrás (llega con ticket `009`) |
| POST | `/oauth2/token` | Intercambio de código por tokens, refresh (`refresh_token`), o `client_credentials` (ticket `048`, ver abajo) |
| POST | `/oauth2/revoke` | Revoca un token emitido por este flujo |
| GET | `/oauth2/jwks` | Claves públicas (JWKS) para verificar la firma de los JWT emitidos |
| GET | `/.well-known/openid-configuration` | Metadata de descubrimiento OIDC estándar |

### `client_credentials` — clientes machine-to-machine (ticket `048`)
Para apps del ecosistema que necesitan autenticarse entre sí sin un usuario humano de por medio (ej. `mail-core-mc`). Verificado en vivo end-to-end.

```
POST /oauth2/token
Authorization: Basic base64(client_id:client_secret)
Content-Type: application/x-www-form-urlencoded

grant_type=client_credentials&scope=mail:send
```
Responde `200` con `{ "access_token", "scope", "token_type": "Bearer", "expires_in" }` — sin `refresh_token` (no aplica a este grant). `400 invalid_scope` si se pide un scope no registrado para ese cliente; `400 invalid_request` si el secreto no coincide.

Solo un `identity_client` con `is_machine_client=true` puede usar este grant — ver `docs/BASE_DE_DATOS.md`. No hay todavía un endpoint de alta de clientes machine-to-machine (mismo estado que la alta de tenants/clientes en general, ticket `008`) — se siembra a mano.

### ⚠️ La clave de firma RSA se regenera en cada arranque
`AuthorizationServerConfig` genera un par de llaves RSA nuevo cada vez que la aplicación arranca — una simplificación deliberada y documentada (ver Javadoc de la clase y `README.md`). Consecuencia real: cualquier `accessToken` emitido antes de un reinicio deja de verificar después de uno. No es apto para producción sin una clave persistida y rotada.

## Login social real — redirect + callback + canje (tickets `036`/`037`/`038`, en construcción)
Primeros tres tickets de `docs/definiciones/login-social-real.md`. **Todavía no es un flujo completo/usable por un usuario final** — no hay botón en `/ui/login`/`/ui/register` ni página `/ui/social-callback` real (ticket `039`), aunque el backend ya está completo: las dos rutas de redirect/callback resuelven tenant+proveedor por request vía `TenantAwareClientRegistrationRepository` (ticket `036`), el callback exitoso ya crea/vincula el `app_user` correspondiente y emite un código de un solo uso (`SocialLoginSuccessHandler`/`SocialLoginFailureHandler`, ticket `037`), y ese código ya se puede canjear por tokens reales (ticket `038`).

| Método | Ruta | Qué hace |
|---|---|---|
| GET | `/oauth2/authorization/{identityClientId}::{provider}` | Redirige a Google/Facebook con las credenciales del tenant dueño de `identityClientId` (`provider` = `google`/`facebook`, case-insensitive). `404`/comportamiento por defecto de Spring si el `registrationId` no resuelve — nunca revela si el problema es el UUID o el proveedor deshabilitado (ver nota de seguridad abajo) |
| GET | `/login/oauth2/code/{identityClientId}::{provider}` | Callback del proveedor tras el consentimiento. Éxito: `SocialLoginSuccessHandler` crea/vincula el `app_user` (HU-1/HU-2), registra un `LoginEvent`, emite un código de un solo uso vía `RedisTokenStore` (`purpose="social-login-exchange"`, TTL 60s) y redirige — **nunca un token real en la URL**. Fallo (consentimiento denegado): `SocialLoginFailureHandler` redirige con `?error=...`. Fallo sin correlación válida (sesión expirada/callback manipulado) o credenciales del tenant rotas: redirige a `/ui/social-login-error` — placeholder sin plantilla real todavía (llega con el ticket `039`), deliberadamente sin theming (no se infiere `client_id` vía `Referer`), y este caso nunca cambia con el `identity_client` resuelto (Decisión 4, ver ticket 037). **A dónde redirige exactamente (ticket `055`):** si `identity_client.hosts_own_login_ui = false` (default, todo cliente anterior al ticket 055), a `/ui/social-callback?client_id=...&code=...`/`/ui/login?client_id=...&error=...` (páginas hospedadas por auth-core-mc); si `hosts_own_login_ui = true` (ej. `galgoth-studio`), directo al `redirect_uri` propio del cliente con `?code=...`/`?error=...` — mismo código de un solo uso, mismo canje, solo cambia quién lo recibe |
| POST | `/api/v1/oauth2/social-exchange` | Canjea el código de un solo uso por tokens reales, salvo que el usuario resuelto tenga 2FA activo (ver ticket `045` arriba). Header `X-Client-Id` (el mismo `client_id` recibido en la query de `/ui/social-callback`, ver nota abajo); body: `code`. `200` + el mismo `{ "user": {...}, "tokens": {...} }` que `/api/v1/login`, o `202` + `TwoFactorRequiredResponse` (mismo shape que `/login`, ver ticket `045`) |

Distinto de `/oauth2/authorize` (arriba): ese es el flujo Authorization Code + PKCE de este servicio actuando como *authorization server* para sus propios clientes; este es el flujo donde este servicio actúa como *cliente* OAuth2 de Google/Facebook para autenticar a un usuario final — `AuthorizationServerConfig` no interviene en ninguna de las tres rutas de esta sección (confirmado, su `securityMatcher` no las incluye).

**Requisito de seguridad:** un `registrationId` con un UUID de `IdentityClient` inexistente y uno con UUID existente pero proveedor deshabilitado deben ser indistinguibles desde afuera — ambos resuelven a `null` en `TenantAwareClientRegistrationRepository` por el mismo camino, sin excepción ni log diferenciado.

**2FA (OQ-8 de la definición) — resuelto por el ticket `045`:** el diseño exige que el 2FA obligatorio del tenant se siga exigiendo tras un login social. Hasta el ticket `045` esto no era el caso — ni el login social ni el password tenían ningún gate de 2FA real, solo el mecanismo autoservicio de `TwoFactorController`/`TwoFactorPreferenceService`. El ticket `045` construyó ese gate (`LoginCompletionService`, ver sección propia arriba) y lo aplicó a ambos flujos de entrada por igual — ver esa sección para el contrato completo.

### `/api/v1/oauth2/social-exchange` — por qué requiere `X-Client-Id` igual que `/login`
El código emitido por `SocialLoginSuccessHandler` solo lleva el `userId` (ver `RedisTokenStore.issue` arriba) — el minter final (`DirectTokenService`, vía `LoginCompletionService` desde el ticket `045`) necesita además un `IdentityClient` first-party para mintear. Para un cliente con `hosts_own_login_ui = false`, ese mismo `client_id` ya viaja en la query del redirect a `/ui/social-callback`, y el helper compartido `AuthCoreUi.call(...)` (`static/js/api.js`) ya adjunta automáticamente ese `client_id` como header `X-Client-Id` en cada llamada — el mismo mecanismo que usa cualquier otro endpoint de este documento, ninguno nuevo. Para un cliente con `hosts_own_login_ui = true` (ticket `055`), el `client_id` **no** viaja en la query (la URL de destino ya es propia de ese cliente) — su propia página conoce su `client_id` de antemano (es el mismo que usa para `/register`/`/login`) y lo adjunta ella misma como `X-Client-Id`. Como con `/login`, si el cliente resuelto no es first-party responde `403 unauthorized_client` **sin consumir el código** (para no quemar un código todavía válido por un error de configuración del lado del cliente).

### Códigos de error de `/api/v1/oauth2/social-exchange`
| HTTP | `error` | Cuándo |
|---|---|---|
| 400 | `validation_error` | Falta `code` en el body |
| 400 | `invalid_token` | El código no existe, ya expiró, ya se usó, o el usuario que resuelve pertenece a un tenant distinto del cliente resuelto por `X-Client-Id` — deliberadamente el mismo código/mensaje genérico para los cuatro casos, mismo criterio que `/token/refresh` |
| 401 | `unknown_client` | El header `X-Client-Id` no corresponde a ningún cliente registrado |
| 403 | `unauthorized_client` | El cliente resuelto por `X-Client-Id` no es first-party |
