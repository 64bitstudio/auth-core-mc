# API — auth-core-mc

> Endpoints del backend: rutas, métodos, qué reciben y qué responden. Explicado en simple. Se actualiza cuando cada ticket que añade o cambia endpoints se mueve a `/done`.

## Estado actual
`/register`, `/login`, `/verify-email/*`, `/change-email/*`, `/password-reset/*`, `/2fa/*`, `/identity-providers/*`, `/token/refresh`, `/token/revoke` y `/oauth2/**`/`/.well-known/openid-configuration` **implementados y probados** (tickets `002`-`007`, en `/done`). `/login` ahora emite tokens reales (ver más abajo). El flujo real de redirect+callback de login social (Google/Facebook, `docs/definiciones/login-social-real.md`) está **en construcción** — `/oauth2/authorization/**` y `/login/oauth2/code/**` resuelven tenant+proveedor por request (ticket `036`), el callback exitoso crea/vincula el `app_user` y emite un código de un solo uso (`SocialLoginSuccessHandler`/`FailureHandler`, ticket `037`), y ese código ya puede canjearse por tokens reales vía `POST /api/v1/oauth2/social-exchange` (ticket `038`, ver sección propia más abajo) — pero todavía no hay ningún botón/página en la UI que dispare el flujo completo end-to-end (ticket `039`).

Este documento describe la superficie **JSON** (`/api/v1/**`, `/oauth2/**`). La UI web (ticket `009`, páginas `/ui/**` que llaman a estos mismos endpoints) está documentada en `docs/COMPONENTES.md`.

### ⚠️ `/identity-providers/*` requiere autenticación (a propósito, no es un descuido)
A diferencia de todos los demás endpoints de este documento, estos **no** están en la lista `permitAll` de `SecurityConfig`. Configurar credenciales OAuth de un tenant es una acción de administración real — dejarla abierta con el mismo modelo de confianza temporal que `/verify-email` o `/2fa` (solo `X-Client-Id`) sería un riesgo real, no uno acotado. Como no existe todavía autenticación de tenant-admin (llega con ticket `007` o uno nuevo), Spring Security la protege con su comportamiento por defecto: **401 para cualquiera**, fail-closed. Es una limitación intencional, no un bug — no la debilites sin agregar autenticación real primero.

### ⚠️ Límite temporal de confianza (hasta ticket 007)
`/verify-email/request` y `/change-email/request` reciben el `userId` directamente en el body — no hay todavía un token de acceso real que identifique "al usuario actual" (eso lo trae ticket `007`). Cualquiera que conozca (o adivine) un `userId` puede disparar el envío de un correo de verificación/cambio para ese usuario — molesto (spam, mitigado por el cooldown de 60s), pero no explotable: completar el flujo requiere poseer el token que llega a esa bandeja de entrada. Documentado también en `TenantScopedUserResolver.java`.

## Convenciones
- **Cómo se identifica el tenant en cada request**: header `X-Client-Id` con el `client_id` de un `IdentityClient` registrado (ver `BASE_DE_DATOS.md`). Si el header no corresponde a ningún cliente registrado, la respuesta es `401 unknown_client`. Esta fue la decisión pendiente que ticket `001` dejó abierta; ticket `002` la resolvió así — el flujo `/oauth2/authorize` de ticket `007` usará en cambio el parámetro estándar `client_id` de OAuth2, no este header (son superficies distintas: esta es la API "directa", esa es el flujo redirect).
- Todas las respuestas de error usan el mismo formato: `{ "error": "codigo_de_error", "message": "explicación" }`.

## Registro y login (ticket `002`, `/login` actualizado en ticket `007`)
| Método | Ruta | Qué hace | Qué recibe | Qué responde |
|---|---|---|---|---|
| POST | `/api/v1/register` | Crea un usuario nuevo | Header `X-Client-Id`; body: `email` o `phone` (uno obligatorio), `password` (min. 8 caracteres, letra+dígito), `nombre`, `apellidos` | `201` + usuario creado (sin `password_hash`) |
| POST | `/api/v1/login` | Verifica credenciales y, si el cliente es first-party, **emite tokens reales** (grant directo, sin redirect — ver ticket `007` en `ARQUITECTURA.md`) | Header `X-Client-Id`; body: `identifier` (email o phone), `password` | `200` + `{ "user": {...}, "tokens": { "accessToken", "refreshToken", "tokenType": "Bearer", "expiresInSeconds" } }` |

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
| POST | `/oauth2/token` | Intercambio de código por tokens, o refresh (grant `refresh_token` estándar) |
| POST | `/oauth2/revoke` | Revoca un token emitido por este flujo |
| GET | `/oauth2/jwks` | Claves públicas (JWKS) para verificar la firma de los JWT emitidos |
| GET | `/.well-known/openid-configuration` | Metadata de descubrimiento OIDC estándar |

### ⚠️ La clave de firma RSA se regenera en cada arranque
`AuthorizationServerConfig` genera un par de llaves RSA nuevo cada vez que la aplicación arranca — una simplificación deliberada y documentada (ver Javadoc de la clase y `README.md`). Consecuencia real: cualquier `accessToken` emitido antes de un reinicio deja de verificar después de uno. No es apto para producción sin una clave persistida y rotada.

## Login social real — redirect + callback + canje (tickets `036`/`037`/`038`, en construcción)
Primeros tres tickets de `docs/definiciones/login-social-real.md`. **Todavía no es un flujo completo/usable por un usuario final** — no hay botón en `/ui/login`/`/ui/register` ni página `/ui/social-callback` real (ticket `039`), aunque el backend ya está completo: las dos rutas de redirect/callback resuelven tenant+proveedor por request vía `TenantAwareClientRegistrationRepository` (ticket `036`), el callback exitoso ya crea/vincula el `app_user` correspondiente y emite un código de un solo uso (`SocialLoginSuccessHandler`/`SocialLoginFailureHandler`, ticket `037`), y ese código ya se puede canjear por tokens reales (ticket `038`).

| Método | Ruta | Qué hace |
|---|---|---|
| GET | `/oauth2/authorization/{identityClientId}::{provider}` | Redirige a Google/Facebook con las credenciales del tenant dueño de `identityClientId` (`provider` = `google`/`facebook`, case-insensitive). `404`/comportamiento por defecto de Spring si el `registrationId` no resuelve — nunca revela si el problema es el UUID o el proveedor deshabilitado (ver nota de seguridad abajo) |
| GET | `/login/oauth2/code/{identityClientId}::{provider}` | Callback del proveedor tras el consentimiento. Éxito: `SocialLoginSuccessHandler` crea/vincula el `app_user` (HU-1/HU-2), registra un `LoginEvent`, emite un código de un solo uso vía `RedisTokenStore` (`purpose="social-login-exchange"`, TTL 60s) y redirige a `/ui/social-callback?client_id=...&code=...` — **nunca un token real en la URL**. Fallo (consentimiento denegado): `SocialLoginFailureHandler` redirige a `/ui/login?client_id=...&error=...` del mismo tenant (theming correcto). Fallo sin correlación válida (sesión expirada/callback manipulado) o credenciales del tenant rotas: redirige a `/ui/social-login-error` — placeholder sin plantilla real todavía (llega con el ticket `039`), deliberadamente sin theming (no se infiere `client_id` vía `Referer`) |
| POST | `/api/v1/oauth2/social-exchange` | Canjea el código de un solo uso por tokens reales — el único paso que efectivamente emite tokens en todo el flujo social. Header `X-Client-Id` (el mismo `client_id` recibido en la query de `/ui/social-callback`, ver nota abajo); body: `code`. `200` + el mismo `{ "user": {...}, "tokens": {...} }` que `/api/v1/login` (misma clase de respuesta, para que `AuthCoreUi.saveSession(...)` no necesite lógica nueva) |

Distinto de `/oauth2/authorize` (arriba): ese es el flujo Authorization Code + PKCE de este servicio actuando como *authorization server* para sus propios clientes; este es el flujo donde este servicio actúa como *cliente* OAuth2 de Google/Facebook para autenticar a un usuario final — `AuthorizationServerConfig` no interviene en ninguna de las tres rutas de esta sección (confirmado, su `securityMatcher` no las incluye).

**Requisito de seguridad:** un `registrationId` con un UUID de `IdentityClient` inexistente y uno con UUID existente pero proveedor deshabilitado deben ser indistinguibles desde afuera — ambos resuelven a `null` en `TenantAwareClientRegistrationRepository` por el mismo camino, sin excepción ni log diferenciado.

**2FA (OQ-8 de la definición):** el diseño exige que el 2FA obligatorio del tenant se siga exigiendo tras un login social. En la práctica hoy `/api/v1/login` (password) no tiene ningún gate de 2FA en el momento del login — `TwoFactorController`/`TwoFactorPreferenceService` son un mecanismo autoservicio desde `/ui/cuenta`, no una puerta de login. `SocialLoginSuccessHandler` no inventó una puerta nueva solo para el login social (habría quedado más estricto que el password, inconsistente y fuera del alcance del ticket `037`) — replica el comportamiento real de hoy: ninguno. Pendiente de decisión del Product Owner: construir un gate de 2FA real en el login (ticket futuro, tocando ambos flujos a la vez) o confirmar que el criterio ya está satisfecho tal cual.

### `/api/v1/oauth2/social-exchange` — por qué requiere `X-Client-Id` igual que `/login`
El código emitido por `SocialLoginSuccessHandler` solo lleva el `userId` (ver `RedisTokenStore.issue` arriba) — `DirectTokenService.issueTokens` (el mismo minter de `/api/v1/login`, sin cambios en su firma) necesita además un `IdentityClient` first-party para mintear. Ese mismo `client_id` ya viaja en la query del redirect a `/ui/social-callback`, y el helper compartido `AuthCoreUi.call(...)` (`static/js/api.js`) ya adjunta automáticamente ese `client_id` como header `X-Client-Id` en cada llamada — el mismo mecanismo que usa cualquier otro endpoint de este documento, ninguno nuevo. Como con `/login`, si el cliente resuelto no es first-party responde `403 unauthorized_client` **sin consumir el código** (para no quemar un código todavía válido por un error de configuración del lado del cliente).

### Códigos de error de `/api/v1/oauth2/social-exchange`
| HTTP | `error` | Cuándo |
|---|---|---|
| 400 | `validation_error` | Falta `code` en el body |
| 400 | `invalid_token` | El código no existe, ya expiró, ya se usó, o el usuario que resuelve pertenece a un tenant distinto del cliente resuelto por `X-Client-Id` — deliberadamente el mismo código/mensaje genérico para los cuatro casos, mismo criterio que `/token/refresh` |
| 401 | `unknown_client` | El header `X-Client-Id` no corresponde a ningún cliente registrado |
| 403 | `unauthorized_client` | El cliente resuelto por `X-Client-Id` no es first-party |
