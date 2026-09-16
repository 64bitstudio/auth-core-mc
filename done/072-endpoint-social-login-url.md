# 072 — Endpoint público para resolver la URL de login social

## Objetivo
Último tramo faltante de `docs/definiciones/login-social-real.md` para
que galgoth-studio (u otro cliente con UI propia) pueda ofrecer login
social real desde su PRIMERA pantalla, sin sesión todavía. Todo lo demás
ya está construido y probado: `hosts_own_login_ui` (ticket 055),
`SocialLoginSuccessHandler`/`FailureHandler`, `/api/v1/oauth2/social-exchange`
(ticket 038), y las credenciales reales de Google/Facebook ya
configuradas para el tenant `galgoth-studio` en dev/qa/prod (verificado
en la base de datos real durante esta sesión).

**El hueco real**: `/oauth2/authorization/{registrationId}` ya es
público, pero `registrationId` empaqueta el UUID interno del
`IdentityClient` (`SocialRegistrationId`, formato
`{identityClientId}::{provider}`) -- un detalle de implementación que
ningún frontend debe conocer ni hardcodear. Hasta ahora, la única forma
de obtener esa URL era `AccountLinkProviderController.linkProvider`, que
exige un JWT (tiene sentido para "vincular", no para "iniciar sesión por
primera vez", donde por definición no hay sesión todavía).

## Criterios de aceptación (TDD)
- `GET /api/v1/oauth2/login-url/{provider}` (header `X-Client-Id`, SIN
  Authorization) devuelve `{redirectUrl}` absoluta, mismo formato que ya
  devuelve `link-provider` para el mismo cliente/proveedor.
- Un proveedor no soportado responde `400 unsupported_provider` (mismo
  criterio que `link-provider`/`unlink-provider`).
- Un `X-Client-Id` desconocido responde `401 unknown_client` (mismo
  criterio que el resto de la API directa).
- No requiere ni acepta ningún token -- verificado explícitamente sin
  header `Authorization`.
- Suite completa en verde.

## Hecho
Implementado, tests reales en verde.

- `SocialLoginUrlController` nuevo (`GET /api/v1/oauth2/login-url/{provider}`,
  público en `SecurityConfig`): resuelve el cliente por `X-Client-Id`,
  valida el proveedor, arma la misma URL que `linkProvider` sin escribir
  ningún `LinkIntentSession` (no hay nada que correlacionar todavía --
  el usuario ni existe o no está identificado hasta que vuelve del
  proveedor).
- `SupportedProvider` nuevo (helper compartido): extrae el
  parseo/validación de proveedor que `AccountLinkProviderController`
  tenía duplicado en 2 sitios -- evita una 3ra copia antes de que Sonar
  la marque (mismo criterio ya aplicado a `JwtAudience`).
- Tests reales end-to-end (Testcontainers, sin JWT): URL absoluta
  correcta para Google/Facebook, proveedor no soportado → 400, cliente
  desconocido → 401.
- Suite completa: 457 tests, 0 fallos, 0 errores.

**Lado galgoth-studio**: cerrado en el ticket 107 de ese repo (PR #152,
mergeado) -- botones de Login/Register habilitados + `/auth/callback`.

**Verificado en vivo end-to-end**: clic real en "Google" desde
`https://studio-dev.galgoth.64bitstudio.com/login` navegó al selector
de cuentas real de Google (`accounts.google.com`), con `client_id`,
`redirect_uri` (`.../login/oauth2/code/{identityClientId}::google`) y
PKCE correctos -- confirma que este endpoint resuelve el
`registrationId` real y que las credenciales de Google para el tenant
`galgoth-studio` funcionan. Detenido ahí a propósito (nunca se completó
el consentimiento con una cuenta real).
