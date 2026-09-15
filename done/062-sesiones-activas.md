# 062 — Sesiones activas (listar y revocar)

## Objetivo
Ver `docs/definiciones/perfil-de-usuario.md` de galgoth-studio (VoBo de
Marco recibido) — HU-6. `refresh_token` hoy no guarda nada de
dispositivo/navegador/actividad — decisión de Marco: dispositivo +
navegador + última actividad (parseado del `User-Agent`), sin
geolocalización de IP.

**Depende de:** nada nuevo.

## Alcance
- **Sí incluye:**
  - Migración: `refresh_token.user_agent TEXT` (nullable),
    `refresh_token.created_at TIMESTAMPTZ NOT NULL DEFAULT now()`,
    `refresh_token.last_used_at TIMESTAMPTZ NOT NULL DEFAULT now()`.
  - `AuthController`/`TokenController`: capturan el header `User-Agent`
    del request (nuevo parámetro `HttpServletRequest` o
    `@RequestHeader`) al emitir un refresh token (login) y lo pasan a
    `DirectTokenService`; cada `POST /api/v1/token/refresh` actualiza
    `last_used_at` del token usado.
  - `GET /api/v1/account/sessions` (autenticado): lista los refresh
    tokens no revocados/no expirados del usuario, con navegador/SO
    parseados del `User-Agent` en el momento de leer (no se guarda ya
    parseado — el dato crudo es la fuente de verdad), `createdAt`,
    `lastUsedAt`, y un flag `current` (comparando contra el token de la
    request actual).
  - `DELETE /api/v1/account/sessions/{id}` (revoca una individual, debe
    pertenecer al usuario autenticado — `404` si no, mismo criterio de
    no revelar existencia ajena) y
    `POST /api/v1/account/sessions/revoke-others` (revoca todas menos la
    actual).
- **No incluye:** geolocalización de IP/ciudad (decisión explícita de
  Marco), revocación automática al cambiar contraseña (ticket `061`,
  quedó como acción independiente).

## Criterios de aceptación (TDD)
- Login real desde dos "dispositivos" (dos `User-Agent` distintos en los
  tests) → `GET /sessions` devuelve ambos, cada uno con su
  navegador/SO correctos.
- La sesión de la request actual aparece marcada `current`.
- Revocar una sesión ajena (de otro usuario) → `404`.
- `revoke-others` deja solo la sesión actual usable (un refresh con
  cualquier otro token después responde inválido).
- Suite completa en verde.
- Verificación en vivo contra DEV: login real desde dos navegadores/
  clientes, confirmar que ambos aparecen y que revocar uno lo invalida.

## Hecho
- Migración `V13`. `RefreshToken` gana `userAgent`/`createdAt`/
  `lastUsedAt` + `touch()` — constructor histórico (4 args) preservado
  sin `userAgent` para no romper callers/tests previos al ticket.
- `DirectTokenService.issueTokens` gana overload de 3 args (con
  `userAgent`); el de 2 args delega con `null` (mismo criterio de
  compatibilidad). `.refresh(...)` llama a `touch()` + guarda.
- **Hallazgo real de "current"**: el JWT de acceso no lleva ninguna
  referencia a qué refresh token lo emitió — no hay forma de derivar
  "cuál sesión es esta" solo del Bearer token. Resuelto con el header
  opcional `X-Current-Refresh-Token` (el refresh token crudo que el
  frontend ya tiene guardado), comparado por hash contra `token_hash`
  (mismo mecanismo que `DirectTokenService.refresh` ya usa) — nunca se
  decodifica nada del JWT para esto.
- `AuthController`/`SocialExchangeController`/`TwoFactorLoginController`
  (los 3 puntos reales donde se emite un refresh token) capturan su
  propio header `User-Agent` y lo pasan a través de
  `LoginCompletionService.complete(client, user, userAgent)` (también
  con overload de 2 args para no romper tests existentes).
- `UserAgentParser` nuevo (sin librería externa — detección simple de
  Chrome/Firefox/Safari/Edge/Opera sobre Windows/macOS/Linux/Android/
  iOS, suficiente para lo que un usuario reconoce en una lista de
  sesiones).
- `AccountSessionsService`/`AccountSessionsController`: `GET`/`DELETE
  /{id}`/`POST /revoke-others`.
- `docs/API.md`/`docs/BASE_DE_DATOS.md` actualizados.
- **Ripple real de cambiar 2 firmas compartidas**: `AuthControllerTest`,
  `SocialExchangeControllerTest`, `TwoFactorLoginControllerTest` y
  `LoginCompletionServiceTest` mockeaban `complete`/`issueTokens` con la
  firma vieja (2 args) — Mockito no relaciona overloads, así que esos
  stubs quedaban sin efecto contra las llamadas reales (3 args) y las
  aserciones fallaban. Corregido actualizando los mocks a la firma real
  (`eq(...), eq(...), any()`), no bajando el estándar de la firma nueva.
- Tests: `AccountSessionsControllerTest` (real JWT/DB, login real por
  `/api/v1/login` para probar el cableado completo, no solo el servicio
  aislado) — dos dispositivos distintos, `current` marcado correctamente,
  revocar una sesión ajena rechazado, revocar una sesión propia invalida
  su refresh token, `revoke-others` deja solo la actual, 401 sin auth.
  Suite completa: 415/415 en verde.
- **Verificación en vivo contra DEV**: dos logins reales con
  `User-Agent` distintos (Chrome/macOS, Safari/iOS) → `GET /sessions`
  las muestra correctamente parseadas, con `current` marcado en la
  correcta vía `X-Current-Refresh-Token`; `DELETE` de un id inventado →
  `404`; `revoke-others` deja inválido el refresh token de la otra
  sesión y funcional el de la actual. Datos de prueba limpiados.
