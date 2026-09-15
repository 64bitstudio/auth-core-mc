# 066 — CORS: falta `X-Current-Refresh-Token` en el allowlist de headers

## Objetivo
Ticket retroactivo. Hallazgo real durante la verificación en vivo de galgoth-studio#093 (Pantalla "Usuario"): `AccountSessionsController.listSessions` (ticket 062) exige el header `X-Current-Refresh-Token` para marcar cuál sesión es "la actual", pero `CorsConfig.allowedHeaders` (ticket 054) nunca lo incluyó — nadie lo había llamado antes desde un navegador cruzando origen (las pruebas de backend no pasan por CORS, y hasta el ticket 093 de galgoth-studio ningún cliente externo consumía `listSessions`). Resultado real, verificado con un preflight OPTIONS de verdad contra dev: `Access-Control-Allow-Headers` solo traía `authorization, content-type` — Chrome bloqueaba la request real con `TypeError: Failed to fetch` antes de que llegara al backend, y `UserView.vue` mostraba "No se pudo cargar tu perfil." (el `catch` cae al mensaje genérico porque un fetch bloqueado por CORS no es un `ApiError`).

## Criterios de aceptación (TDD)
- `CorsConfigTest`: el allowlist de headers incluye `X-Current-Refresh-Token`.
- `CorsConfigurationIntegrationTest`: un preflight real a `/api/v1/account/sessions` pidiendo `x-current-refresh-token` responde `200` con ese header presente en `Access-Control-Allow-Headers`.
- Verificado en vivo contra dev: `accountApi.listSessions()` desde `studio-dev.galgoth.64bitstudio.com` ya no lanza "Failed to fetch".

## Hecho
Fix de una línea en `CorsConfig.allowedHeaders` (`backend/src/main/java/com/mcortes/authcoremc/security/CorsConfig.java`) — se agregó `X-Current-Refresh-Token` a la lista explícita ya existente (`Content-Type`, `X-Client-Id`, `Authorization`).

Tests nuevos:
- `CorsConfigTest.theAllowlistIncludesTheCurrentRefreshTokenHeaderUsedByListSessions` (unit, sin contexto Spring).
- `CorsConfigurationIntegrationTest.aRealPreflightForListSessionsGetsTheCurrentRefreshTokenHeaderAllowed` (end-to-end, MockMvc + Testcontainers) — reproduce el preflight exacto que fallaba antes del fix.

Ambos verdes localmente antes de abrir el PR. CI de Jenkins verde (ver PR). Verificado en vivo contra dev después del deploy: `GET /api/v1/account/sessions` desde el navegador (origen `studio-dev.galgoth.64bitstudio.com`) ya responde `200` con la lista real de sesiones, como parte de la verificación en vivo de galgoth-studio#093.

No rompe compatibilidad — es una ampliación estrictamente aditiva del allowlist existente.
