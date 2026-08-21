# API — auth-core-mc

> Endpoints del backend: rutas, métodos, qué reciben y qué responden. Explicado en simple. Se actualiza cuando cada ticket que añade o cambia endpoints se mueve a `/done`.

## Estado actual
`/register`, `/login`, `/verify-email/*`, `/change-email/*`, `/password-reset/*` y `/2fa/*` **implementados y probados** (tickets `002`-`005`, en `/done`). El resto de la superficie sigue planeada, se irá confirmando conforme avancen los tickets `006` y `007`.

### ⚠️ Límite temporal de confianza (hasta ticket 007)
`/verify-email/request` y `/change-email/request` reciben el `userId` directamente en el body — no hay todavía un token de acceso real que identifique "al usuario actual" (eso lo trae ticket `007`). Cualquiera que conozca (o adivine) un `userId` puede disparar el envío de un correo de verificación/cambio para ese usuario — molesto (spam, mitigado por el cooldown de 60s), pero no explotable: completar el flujo requiere poseer el token que llega a esa bandeja de entrada. Documentado también en `TenantScopedUserResolver.java`.

## Convenciones
- **Cómo se identifica el tenant en cada request**: header `X-Client-Id` con el `client_id` de un `IdentityClient` registrado (ver `BASE_DE_DATOS.md`). Si el header no corresponde a ningún cliente registrado, la respuesta es `401 unknown_client`. Esta fue la decisión pendiente que ticket `001` dejó abierta; ticket `002` la resolvió así — el flujo `/oauth2/authorize` de ticket `007` usará en cambio el parámetro estándar `client_id` de OAuth2, no este header (son superficies distintas: esta es la API "directa", esa es el flujo redirect).
- Todas las respuestas de error usan el mismo formato: `{ "error": "codigo_de_error", "message": "explicación" }`.

## Registro y login (ticket `002`)
| Método | Ruta | Qué hace | Qué recibe | Qué responde |
|---|---|---|---|---|
| POST | `/api/v1/register` | Crea un usuario nuevo | Header `X-Client-Id`; body: `email` o `phone` (uno obligatorio), `password` (min. 8 caracteres, letra+dígito), `nombre`, `apellidos` | `201` + usuario creado (sin `password_hash`) |
| POST | `/api/v1/login` | Verifica credenciales (first-party, sin redirect — ver ticket `007` para el flujo OAuth2 completo) | Header `X-Client-Id`; body: `identifier` (email o phone), `password` | `200` + usuario autenticado. **No emite tokens todavía** — eso es responsabilidad de ticket `007`; este endpoint solo prueba que las credenciales son válidas |

### Códigos de error de `/register` y `/login`
| HTTP | `error` | Cuándo |
|---|---|---|
| 400 | `weak_password` | Password no cumple la política mínima |
| 400 | `invalid_request` | Email/teléfono con formato inválido, o ninguno de los dos presente |
| 400 | `validation_error` | Falta `nombre`/`apellidos`/`password` en el body |
| 401 | `unknown_client` | El header `X-Client-Id` no corresponde a ningún cliente registrado |
| 401 | `invalid_credentials` | Login: identificador o password incorrectos (mensaje genérico a propósito, para no revelar cuál de los dos falló) |
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

## Verificación y cambio de correo (ticket `003`)
| Método | Ruta | Qué hace |
|---|---|---|
| POST | `/api/v1/verify-email/request` | Envía correo de verificación |
| POST | `/api/v1/verify-email/confirm` | Confirma con el token del correo |
| POST | `/api/v1/change-email/request` | Inicia cambio de correo (envía confirmación al correo nuevo) |
| POST | `/api/v1/change-email/confirm` | Confirma el cambio con el token |

## Recuperación de contraseña (ticket `004`)
| Método | Ruta | Qué hace |
|---|---|---|
| POST | `/api/v1/password-reset/request` | Solicita reset (por correo o SMS según el usuario) |
| POST | `/api/v1/password-reset/confirm` | Aplica la nueva contraseña con el token |

## 2FA (ticket `005`)
| Método | Ruta | Qué hace |
|---|---|---|
| POST | `/api/v1/2fa/otp/request` | Envía un código OTP (SMS o correo) |
| POST | `/api/v1/2fa/otp/verify` | Verifica el código OTP |
| POST | `/api/v1/2fa/totp/enroll` | Genera secreto TOTP (para escanear en Authenticator) |
| POST | `/api/v1/2fa/totp/verify` | Verifica un código TOTP |

## Configuración de login social por tenant (ticket `006`)
| Método | Ruta | Qué hace |
|---|---|---|
| GET | `/api/v1/tenants/{tenantId}/identity-providers` | Lista proveedores configurados (sin exponer el secret) |
| PUT | `/api/v1/tenants/{tenantId}/identity-providers/{provider}` | Habilita/deshabilita y configura `client_id`/`client_secret` |

## OAuth2 / OIDC (ticket `007`)
| Método | Ruta | Qué hace |
|---|---|---|
| GET | `/oauth2/authorize` | Inicio del flujo Authorization Code + PKCE (estándar Spring Authorization Server) |
| POST | `/oauth2/token` | Intercambio de código por tokens, o refresh |
| POST | `/oauth2/revoke` | Revoca un token |
| GET | `/.well-known/openid-configuration` | Metadata OIDC estándar |
