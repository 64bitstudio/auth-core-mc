# 022 — Reactivar tenant + confirmación antes de desactivar

## Objetivo
Tercer ticket del rediseño de UI definido en `docs/definiciones/rediseno-ui-completo.md` (HU-6, HU-7). `Tenant.reactivate()` ya existe en el dominio desde el ticket 013 pero ningún endpoint lo expone — este ticket agrega el backend faltante y, de paso, un diálogo de confirmación explícita (no `confirm()` nativo del navegador) compartido tanto por "desactivar" (hoy un clic sin preguntar) como por el nuevo "reactivar".

**Depende de:** ticket 020 (sistema visual + shell del panel).

## Criterios de aceptación (TDD)
- `POST /api/v1/admin/tenants/{id}/reactivate` nuevo en `AdminTenantController`, delegando a `AdminTenantService.reactivate(actorRole, targetTenantId)` — mismo patrón exacto que `deactivate` (solo `platform_admin`, usa `Tenant.reactivate()` ya existente). Sin migración nueva (`deactivated_at` ya es nullable).
- Un `tenant_admin` intentando reactivar recibe 403 real — mismo nivel de permiso que desactivar.
- Prueba end-to-end real: tenant desactivado → reactivado vía el endpoint → login/registro vuelve a funcionar de inmediato (mismo choke-point `ClientContextResolver` del ticket 013).
- Diálogo de confirmación propio en HTML/CSS (no `confirm()` nativo) compartido por ambas acciones — la acción solo ocurre tras una segunda confirmación explícita; cancelar no dispara ninguna llamada al backend.
- UI en `/ui/admin/tenants`: botón "Reactivar" visible solo en tenants desactivados, "Desactivar" solo en tenants activos.

## Hecho
- `POST /api/v1/admin/tenants/{id}/reactivate` nuevo, mismo patrón que `deactivate` (`platform_admin`-only, delega en `Tenant.reactivate()` ya existente desde ticket 013).
- `AdminTenantService.reactivate(actorRole, targetTenantId)` nuevo.
- Diálogo `<dialog class="confirm-dialog">` compartido por "Desactivar" y "Reactivar" en `/ui/admin/tenants`, mensaje ajustado según la acción; cancelar cierra sin llamar al backend.
- Botones "Desactivar"/"Reactivar" agregados a la columna Acciones (condicionados a `tenant.active`) — no existían en la UI antes de este ticket.
- Bug de layout encontrado en vivo (dos botones por celda se recortaban dentro del scroll horizontal de la tabla) — corregido apilándolos con un wrapper `.actions-cell-inner` (flex-column), no dándole `display:flex` al `<td>`.
- Prueba end-to-end real: tenant creado → registro funciona → se desactiva vía el endpoint → registro da 403 (`tenant_deactivated`) → se reactiva vía el endpoint → registro vuelve a funcionar de inmediato.
- Probado en vivo en el navegador: confirmar/cancelar ambas acciones, cambio de estado reflejado sin recargar.
- 259/259 tests en verde (4 nuevos). Detalle completo en `docs/ARQUITECTURA.md` (sección "Ticket 022").
