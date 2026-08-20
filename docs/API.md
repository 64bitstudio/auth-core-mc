# API — auth-core-mc

> Endpoints del backend: rutas, métodos, qué reciben y qué responden. Explicado en simple. Se actualiza cuando cada ticket que añade o cambia endpoints se mueve a `/done`.

## Estado actual
**Pendiente de implementación.** Esta es la superficie planeada, se irá confirmando endpoint por endpoint conforme se completen los tickets `002` a `007`.

## Convenciones
- Todas las rutas van prefijadas por tenant o se resuelven por el `client_id` del llamador (a definir el detalle exacto al implementar `001`).
- Todas las respuestas de error usan el mismo formato: `{ "error": "codigo_de_error", "message": "explicación" }`.

## Registro y login (ticket `002`)
| Método | Ruta | Qué hace | Qué recibe | Qué responde |
|---|---|---|---|---|
| POST | `/api/v1/register` | Crea un usuario nuevo | `email` o `phone` (uno obligatorio), `password`, `nombre`, `apellidos` | Usuario creado (sin password_hash) |
| POST | `/api/v1/login` | Login directo (first-party, ver ticket `007`) | `identifier` (email o phone), `password` | `access_token`, `refresh_token` o requerimiento de 2FA |

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
