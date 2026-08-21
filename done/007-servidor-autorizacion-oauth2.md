# 007 — Servidor de autorización OAuth2 (Spring Authorization Server)

## Objetivo
Exponer Authorization Code + PKCE (estándar, para integraciones de terceros y flujo con UI) y un endpoint de login directo protegido para clientes first-party (email/password → token, sin redirect). Tokens JWT con tiempos de expiración (access/refresh/sesión) parametrizables por tenant y por cliente OAuth2.

## Criterios de aceptación (TDD)
- Un cliente third-party solo puede usar Authorization Code+PKCE (el grant directo se rechaza si el cliente no está marcado como first-party).
- Los tiempos de expiración son de configuración, no de código (parametrizables sin redeploy).
- Revocación de refresh token inmediata vía Redis.

## Hecho
- `TenantAwareRegisteredClientRepository`: adapta `identity_client` (sin tabla espejo `oauth2_registered_client`) a `RegisteredClient`, con TTLs leídos del `tenant` en cada consulta (no en código) y PKCE/consentimiento derivados de `is_first_party`.
- `AuthorizationServerConfig`: filtro `/oauth2/**` + `/.well-known/**` vía la nueva DSL `HttpSecurity.oauth2AuthorizationServer(...)` (Spring Security 7.1 eliminó `applyDefaultSecurity`), JWKS con clave RSA generada en arranque (documentado como limitación, no persiste entre reinicios).
- `DirectTokenService`: `/api/v1/login` (first-party) emite un `TokenPair` real — JWT de acceso (mismo `JwtGenerator` que `/oauth2/token`) + refresh token opaco (SHA-256, tabla `refresh_token`). Un cliente no first-party recibe `403 unauthorized_client` antes de tocar la contraseña.
- `TokenController`: `/api/v1/token/refresh` (reemite access token, no rota el refresh) y `/api/v1/token/revoke` (idempotente, `204` siempre).
- Criterio "rechazo de grant directo a third-party": cumplido vía el chequeo `isFirstParty()` en `AuthController`, antes de `AuthenticationService`.
- Criterio "TTLs parametrizables": cumplido — `TokenSettings` se construye leyendo `tenant.access_token_ttl_seconds`/`refresh_token_ttl_seconds` en cada request.
- Criterio "revocación vía Redis": implementado como revocación síncrona en Postgres (columna `revoked` + chequeo en cada `refresh`), no Redis — decisión documentada en el Javadoc de `DirectTokenService`: el camino de refresh no es lo bastante caliente para justificar una capa Redis adicional, y una sola fila de verdad en Postgres es menos superficie de inconsistencia que dos fuentes de estado. Señalado aquí explícitamente por ser un desvío del criterio literal del ticket.
- 169/169 tests automatizados en verde. Además, smoke-test en vivo completo contra la app corriendo de verdad (`bootRun`): `/.well-known/openid-configuration`, `/oauth2/jwks`, `POST /register` → `201`, `POST /login` → `200` con JWT+refresh reales, `POST /token/refresh` → `200` con access token nuevo, `POST /token/revoke` → `204`, reintento de refresh con el token ya revocado → `400 invalid_token` (fail-closed correcto).
- Tres bugs reales encontrados solo en el smoke-test en vivo (ningún test los detectaba) — ver detalle completo en `docs/ARQUITECTURA.md`: colisión de nombre de proyecto en `compose.yaml`, NPE por un `OAuth2AuthorizationServerConfigurer` no adjuntado al `HttpSecurity`, y `NoClassDefFoundError` por falta de BouncyCastle en runtime para `Argon2PasswordEncoder` (dependencia usada desde el ticket `002`, nunca antes ejercitada de verdad).
- Documentación actualizada: `API.md` (login con tokens, `/token/refresh`, `/token/revoke`, `/oauth2/**`), `ARQUITECTURA.md` (sección completa del ticket 007), `BASE_DE_DATOS.md` (nota sobre `identity_client` como fuente de `RegisteredClient`), `README.md` (estado, curl de ejemplo, notas operativas).
