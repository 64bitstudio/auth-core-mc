# 037 — `SocialLoginSuccessHandler` / `SocialLoginFailureHandler`

## Objetivo
Nace de `docs/definiciones/login-social-real.md` (HU-1, HU-2, HU-3; Diseño técnico, decisiones 3, 4 y 5). Implementa qué pasa cuando el callback de Google/Facebook llega con éxito o con fallo: resolución/creación/vinculación de `app_user`, emisión del código de un solo uso, y el camino de error sin theming.

**Depende de:** tickets 035 (`external_identity`) y 036 (repositorio/wiring de `oauth2Login`). Es prerequisito de 038.

## Criterios de aceptación (TDD)
- `SocialLoginSuccessHandler` (HU-1/HU-2):
  - Valida que el correo esté verificado por el proveedor: Google vía claim `email_verified=true` del ID token; Facebook vía presencia del campo `email` en el perfil (Facebook solo lo entrega si ya está verificado por su lado).
  - Si Facebook no entrega correo (permiso no otorgado): bloquea el login social con Facebook, mensaje claro pidiendo reintentar o usar otro método — **no** inventa un identificador alterno.
  - Busca `app_user` por `(tenant_id, email)`. Si no existe: crea `app_user` nuevo + fila en `external_identity`. `email_verified` de la cuenta nueva refleja lo que reportó el proveedor (`true` si el proveedor lo confirmó; si no, se crea igual con `email_verified=false` y queda sujeta al flujo de verificación normal ya existente, ticket 003 — no se bloquea la creación).
  - Si existe un `app_user` con ese email **y el proveedor lo reporta verificado**: auto-vincula (crea fila en `external_identity` contra ese `user_id`) — sin pedir confirmación de password. Si el usuario ya tiene ese mismo proveedor vinculado, no duplica la fila (constraint `UNIQUE(user_id, provider)` ya lo impide; el handler debe manejar ese camino sin lanzar un 500).
  - Si el tenant tiene 2FA habilitado/obligatorio para el usuario resultante: el login social no lo saltea — el flujo continúa hacia el paso de 2FA existente antes de considerar la sesión completa (mismo criterio que ya aplica a login con password).
  - Emite un código de un solo uso vía `RedisTokenStore.issue("social-login-exchange", userId, Duration.ofSeconds(60))` y redirige a `/ui/social-callback?client_id=...&code=...` — **nunca tokens reales en la URL/redirect**.
- `SocialLoginFailureHandler` (HU-3):
  - Cancelación/negación del consentimiento en el proveedor → redirige a `/ui/login` del mismo tenant (theming correcto) con mensaje de error visible vía el `showStatus()` ya existente, sin crear cuenta ni sesión.
  - Sesión expirada o callback manipulado (Spring lanza `OAuth2AuthenticationException` porque no hay `OAuth2AuthorizationRequest` correlacionado — esto ocurre **antes** de que este handler o cualquier código de tenant/usuario se ejecute): redirige a una página de error genérica, **sin theming** (no se infiere `client_id` vía `Referer`).
  - Credenciales del tenant rotas (secret vencido, app deshabilitada del lado de Google): mismo mensaje de error genérico al usuario final — sin diagnóstico adicional para el admin del tenant (confirmado fuera de alcance en la definición).
- Tests cubriendo: alta nueva con email verificado, alta nueva con email no verificado, vinculación automática exitosa, intento de vincular un proveedor ya vinculado (no debe fallar con 500), Facebook sin email, cancelación de consentimiento, sesión expirada, y que ninguno de estos caminos deja una cuenta o sesión a medio crear si algo falla a mitad del proceso.

## Hecho
- `SocialLoginSuccessHandler`/`SocialLoginFailureHandler` + `SocialLoginUserResolver` (transaccional, separado del handler) + `SocialRegistrationId` (parseo extraído de `TenantAwareClientRegistrationRepository`, compartido). Implementa exacto el diagrama de secuencia "Login social exitoso" de la definición.
- **HU-2, camino rápido:** login social repetido resuelve por `external_identity` (provider+providerUserId) primero, sin pasar por el gate de email-verificado (ese gate solo aplica a vínculos NUEVOS) — `catch (DataIntegrityViolationException)` sobre `UNIQUE(user_id, provider)` como defensa adicional para condiciones de carrera, no como camino principal.
- **Hallazgo real, confirmado con el Product Owner — OQ-8 (2FA):** `/api/v1/login` (password) hoy NO tiene ningún gate de 2FA real (es 100% autoservicio desde `/ui/cuenta`) — no había nada que "reutilizar". El login social queda consistente (sin gate nuevo, mismo comportamiento que password). **Ticket 045 abierto** para un gate de 2FA real que aplique a ambos flujos por igual, no inventado solo aquí.
- **Caso borde no escrito en el ticket, confirmado con el Product Owner:** cuenta existente con ese email pero el proveedor NO la reporta verificada → bloqueado con `social_login_email_conflict` (ni auto-vincula por R-1, ni duplica cuenta por la constraint de unicidad — única salida segura dado el modelo de datos).
- Fallo/cancelación (HU-3): `/ui/login` themed con `?error=social_login_cancelled` para cancelación explícita; `/ui/social-login-error` sin theming para sesión expirada/`state` inválido/credenciales rotas — distinguido vía `OAuth2ErrorCodes.ACCESS_DENIED`. `/ui/social-callback` y `/ui/social-login-error` son rutas placeholder (dan 404 hoy) — sus plantillas reales llegan en el ticket 039, coordinado explícitamente en código/docs.
- 308/308 tests en verde (23 nuevos). Quality Gate de SonarQube verificado en local antes de reportar: OK, 0 violaciones nuevas (3 corregidas antes: `java:S1068` campo sin uso tras refactor, `java:S7467` variable de catch sin usar, `java:S6068` `eq()` redundante).
- No aplica verificación en vivo (no hay UI todavía, eso es el ticket 039) ni Postman (rutas de redirect de browser, no endpoints JSON — mismo criterio que el ticket 036).
- Detalle completo en `docs/ARQUITECTURA.md` (sección "Ticket 037").
