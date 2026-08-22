# 038 — Endpoint `POST /api/v1/oauth2/social-exchange`

## Objetivo
Nace de `docs/definiciones/login-social-real.md` (Diseño técnico, decisión 5). Canjea el código de un solo uso emitido por `SocialLoginSuccessHandler` (ticket 037) por tokens reales, reutilizando el mismo minter que ya usa el grant directo — cierra el círculo entre "Google/Facebook confirmaron la identidad" y "el usuario tiene una sesión real en `auth-core-mc`".

**Depende de:** ticket 037 (necesita que el código exista). Es el último paso del flujo backend antes de la UI (ticket 039).

## Criterios de aceptación (TDD)
- `POST /api/v1/oauth2/social-exchange`, body `{ code }`. Consume el código vía `RedisTokenStore.consume(...)` (mismo `purpose` `"social-login-exchange"` del ticket 037) — de un solo uso real, un segundo intento con el mismo código falla explícitamente.
- Código expirado o inexistente → error explícito (4xx), sin filtrar detalle interno de por qué (mismo criterio de "fallar honesto sin exponer de más" que el resto del proyecto).
- Código válido → llama a `DirectTokenService` (el mismo minter que ya usa `/api/v1/login`, **sin modificar su firma**) para el `userId` resuelto, devuelve el mismo shape JSON `{ user, tokens }` que `/api/v1/login` — así el cliente puede reutilizar `AuthCoreUi.saveSession(...)` sin lógica nueva.
- Endpoint público (no requiere JWT previo — es el paso que *otorga* la sesión), pero el código de un solo uso es la única credencial válida; sin código no hay forma de invocar `DirectTokenService` desde aquí.
- Tests: canje exitoso, código ya usado, código expirado, código inexistente, formato de respuesta idéntico al de `/api/v1/login` en un caso exitoso comparable.

## Hecho
- **`POST /api/v1/oauth2/social-exchange`** (`SocialExchangeController`, header `X-Client-Id` + body `{ code }`): consume el código vía `RedisTokenStore.consume(EXCHANGE_PURPOSE, code)` (de un solo uso real, verificado con Redis real en `SocialExchangeEndToEndTest`), resuelve el `User` y llama a `DirectTokenService.issueTokens(client, user)` — mismo minter que `/api/v1/login`, sin tocar su firma. Respuesta `200` con el mismo shape `LoginResponse { user, tokens }` que `/api/v1/login` — reutiliza la clase existente, no se creó un DTO nuevo.
- **`X-Client-Id` requerido, mismo patrón que `/api/v1/login`:** el código de un solo uso solo lleva el `userId` (`SocialLoginSuccessHandler`, ticket 037) — el minter necesita además un `IdentityClient` first-party. El mismo `client_id` ya viaja en la query del redirect a `/ui/social-callback` y `AuthCoreUi.call(...)` ya lo adjunta automáticamente como header en cada llamada (mecanismo existente, ninguno nuevo). Cliente no first-party → `403` **sin consumir el código** (mismo orden que `AuthController`, para no quemar un código válido por un error de configuración del cliente).
- **Verificación cruzada de tenant** (defensiva, no alcanzada por el flujo real ya que el mismo `IdentityClient` resuelve redirect y canje): si el tenant del usuario resuelto no coincide con el del cliente resuelto por `X-Client-Id`, falla con el mismo error genérico `invalid_token` que un código inválido/expirado — nunca revela cuál de las dos cosas no coincidió.
- **Errores explícitos y sin filtrar detalle interno:** `400 validation_error` (falta `code`), `400 invalid_token` (código inexistente/expirado/ya usado/tenant cruzado — los cuatro casos indistinguibles a propósito), `401 unknown_client` (`X-Client-Id` no resuelve), `403 unauthorized_client` (cliente no first-party). Documentado en `docs/API.md`.
- **Endpoint agregado a `permitAll`** en `SecurityConfig` (es el paso que otorga la sesión, no uno que la requiere) y `EXCHANGE_PURPOSE` de `SocialLoginSuccessHandler` ensanchado a `public` para que el controller lo reutilice sin duplicar el literal.
- **11 tests nuevos**: `SocialExchangeControllerTest` (8, `@WebMvcTest` con mocks) + `SocialExchangeEndToEndTest` (3, Testcontainers reales — Redis, DB, `DirectTokenService` real — prueba la garantía real de un solo uso, no solo el mock). 319/319 tests del proyecto en verde. Quality Gate de SonarQube verificado en local antes del PR: `OK` (0 violaciones nuevas, 91.8% cobertura nueva).
- **Sin Postman:** confirmado que el proyecto no tiene ninguna colección Postman (`/postman`) — mismo estado que dejaron los tickets 036/037, nada que actualizar.
