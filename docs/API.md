# API — auth-core-mc

> Endpoints del backend: rutas, métodos, qué reciben y qué responden. Explicado en simple. Se actualiza cuando cada ticket que añade o cambia endpoints se mueve a `/done`.

## Estado actual
`/register` y `/login` **implementados y probados** (ticket `002`, en `/done`). El resto de la superficie sigue planeada, se irá confirmando conforme avancen los tickets `003` a `007`.

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
