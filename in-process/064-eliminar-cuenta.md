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
