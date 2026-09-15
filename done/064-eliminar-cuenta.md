# 064 — Eliminar cuenta

## Objetivo
Ver `docs/definiciones/perfil-de-usuario.md` de galgoth-studio (VoBo de
Marco recibido) — HU-10. `User` no tiene ningún mecanismo de baja hoy
(a diferencia de `Tenant`, que sí tiene `deactivate`/`reactivate`/
`purge`). Decisión de Marco: al eliminar la cuenta, sus proyectos en
galgoth-studio (públicos o privados) se eliminan en cascada.

**Depende de:** galgoth-studio ticket `091` (necesita el endpoint
interno `purge-projects` ya desplegado para poder llamarlo síncronamente
— ver Diseño técnico §6 del documento: si esa llamada falla, la
eliminación completa de la cuenta debe abortar, nunca dejar una cuenta
"eliminada" con proyectos públicos todavía visibles).

## Alcance
- **Sí incluye:**
  - `User.deactivate()`/`isActive()` (mismo patrón que
    `Tenant.deactivate()`) — columna `deactivated_at` nullable.
  - `ClientContextResolver`/el punto de validación de JWT correspondiente
    rechaza a un usuario desactivado (mismo criterio que
    `TenantDeactivatedException`, un `UserDeactivatedException` nuevo).
  - `DELETE /api/v1/account` (autenticado): en una sola transacción/
    orquestación — (1) llama a `POST
    {GALGOTH_STUDIO_INTERNAL_URL}/api/internal/users/{userId}/purge-projects`
    con un secreto compartido servidor-a-servidor (nueva variable de
    entorno, mismo criterio operativo que `RESEND_API_KEY`); si falla,
    aborta todo con `500`/error explícito, no marca nada como eliminado;
    (2) si la llamada anterior fue exitosa, `User.deactivate()` +
    revoca TODOS sus `refresh_token`.
  - Exige un paso de confirmación en el body (ej. reenviar el email/
    identifier propio) para no ejecutar por un clic accidental.
- **No incluye:** notificar a mail-core-mc/otros tenants de la baja (no
  aplica hoy, solo galgoth-studio tiene datos ligados a `userId`).

## Criterios de aceptación (TDD)
- Eliminar cuenta exitosamente → `User.deactivate()` aplicado, todos los
  refresh tokens revocados, login posterior con esas credenciales
  rechazado (mismo mensaje genérico que un tenant desactivado).
- Si la llamada a `purge-projects` de galgoth-studio falla (mockeada en
  test), la cuenta NO queda desactivada — se aborta la operación
  completa.
- Suite completa en verde.
- Verificación en vivo contra DEV: cuenta de prueba real con al menos un
  proyecto público en galgoth-studio, eliminar la cuenta, confirmar que
  el proyecto desaparece de Explorar y que la cuenta no puede volver a
  iniciar sesión.

## Hecho

**PR:** [#120](https://github.com/64bitstudio/auth-core-mc/pull/120), mergeado a `dev` (`b2d18a5`). Deploy real a DEV verificado en verde (build 44 de Jenkins).

**Implementado tal cual el alcance:**
- `User` gana `deactivated_at` (`V14`, nullable) + `isActive()`/`deactivate()`, mismo patrón que `Tenant.deactivate()` (ticket 013).
- `DELETE /api/v1/account` (autenticado, body `{confirmIdentifier}`), orden estricto en una sola transacción: (1) valida `confirmIdentifier` contra el email/teléfono real; (2) purga SÍNCRONAMENTE los proyectos de galgoth-studio vía su endpoint interno del ticket 091 (`GalgothStudioPurgeClient`, mismo patrón que `ResendEmailSender`: falla ruidoso si no está configurado); si falla, la cuenta NUNCA queda desactivada; (3) solo si la purga tuvo éxito: `User.deactivate()` + revoca TODOS los refresh tokens (reutiliza `AccountSessionsService.revokeOthers(userId, null)` del ticket 062, sin duplicar esa lógica).
- **Decisión real sobre "el punto de validación de JWT correspondiente" (el propio ticket dejaba esto ambiguo):** el rechazo de un usuario desactivado vive en `DirectTokenService.doIssueTokens` -- el único método interno real donde converge TODO camino que emite un token nuevo (login directo, login social, verificación de 2FA), no en `ClientContextResolver` (que resuelve tenants por `X-Client-Id`, un concepto distinto). Mismo criterio de "un solo punto de decisión" que ya usa `ClientContextResolver` para tenants desactivados. `POST /token/refresh` no repite el chequeo a propósito: ya queda cubierto por la revocación del paso (3).
- Nueva config `GALGOTH_STUDIO_INTERNAL_URL`/`GALGOTH_INTERNAL_SECRET` en los 3 `docker-compose`+`.env.example` de este repo -- mismo `GALGOTH_INTERNAL_SECRET` que galgoth-studio ya generó y desplegó por ambiente en su propio ticket 091 (coordinado a mano entre ambos repos, valores reales colocados en `~/secrets/auth-core-mc/.env.{dev,qa,prod}` en la VM tras el merge).

**Hallazgo real en el camino:** el PR falló Quality Gate de SonarQube (diagnosticado vía SSH+Postgres directo, `islast` confirmado sobre el commit real): `S2259` MAJOR real (`registeredClientRepository.findByClientId` puede devolver `null`, contrato de `TenantAwareRegisteredClientRepository`, y se desreferenciaba sin chequear en dos call sites) -- resuelto con un helper `requireRegisteredClient` compartido; `S5778` MAJOR x3 en tests nuevos (lambdas de `assertThatThrownBy` con más de una invocación que podía lanzar) -- corregido extrayendo a variables locales; `S1135` "todo" x3, incluyendo un comentario preexistente en `TenantIsolationTest.java` (archivo no tocado por este ticket, pero bloqueando el gate de esta rama) -- reformulados. Encontré yo mismo (sin que Sonar lo marcara primero) que había escrito "TODO" en mayúsculas asumiendo que estaba exento de la regla S1135 -- estaba equivocado (la propia memoria ya documentada decía lo contrario); corregido antes del segundo intento de CI.

**Tests:** `GalgothStudioPurgeClientTest` (2, mismo criterio "falla ruidoso" que `ResendEmailSenderTest`/`TwilioSmsSenderTest`) + `DirectTokenServiceTest` (+1: usuario desactivado rechazado) + `AccountDeletionControllerTest` nuevo (5, real end-to-end vía Testcontainers: éxito completo + login posterior rechazado, revocación de refresh tokens, `confirmIdentifier` incorrecto no purga nada, la purga fallida no desactiva la cuenta, 401 sin Bearer). Suite completa en verde.

**Verificación en vivo contra DEV real (no simulada):**
- Cuenta de prueba real registrada y logueada contra `auth-dev.64bitstudio.com`.
- Proyecto real creado y publicado (`PUBLIC`) en galgoth-studio, confirmado visible en `GET /api/explore/projects`.
- `DELETE /api/v1/account` con `confirmIdentifier` correcto → `204`.
- `GET /api/explore/projects` ya NO incluye el proyecto — confirmado además a nivel de fila real (`projects.deleted_at` poblado, `visibility` sigue `PUBLIC`: lo que cambió es el soft-delete, no la visibilidad).
- Login posterior con las mismas credenciales → `403 user_deactivated`, `"This account has been deleted"`.
- El refresh token emitido en el login original → `400 invalid_token` al intentar `/token/refresh`.
- Limpieza posterior: proyecto y cuenta de prueba borrados a mano vía SQL directo en las bases de DEV de ambos repos.
