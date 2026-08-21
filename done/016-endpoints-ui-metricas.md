# 016 — Endpoints y UI de métricas de uso por cliente

## Objetivo
Endpoints y vista en el panel para consultar métricas de uso de un cliente: volumen de logins éxito/fallo, desglose por proveedor, usuarios activos/registrados, tasa de error y latencia. Nace de `docs/definiciones/panel-administracion-clientes.md` (HU-4).

**Depende de:** ticket 015 (`login_event` debe existir y estar poblándose) y tickets 011/012 (acceso gateado por rol).

## Criterios de aceptación (TDD)
- `GET /api/v1/admin/tenants/{id}/metrics?from=&to=` — agregaciones sobre `login_event`: volumen por resultado, desglose por proveedor, usuarios activos/registrados, error/latencia.
- La UI muestra un rango de fechas seleccionable.
- Un tenant sin actividad reciente muestra un estado vacío claro, no un error.
- Acceso: `platform_admin` ve cualquier tenant, `tenant_admin` solo el suyo.

## Hecho
- `GET /api/v1/admin/tenants/{id}/metrics?from=&to=` (mismo `AdminTenantController` del ticket 013) — agregaciones reales sobre `login_event`: volumen por resultado, desglose por proveedor, usuarios activos/registrados, tasa de error, latencia promedio.
- Acceso: `platform_admin` cualquier tenant, `tenant_admin` solo el suyo — mismo `AdminAccessPolicy` de grano fino ya usado en el ticket 013, probado 403 real end-to-end.
- Rango de fechas seleccionable en la UI (`/ui/admin/metrics`), con default de últimos 30 días si no se elige nada.
- Un tenant sin actividad reciente muestra un estado vacío claro ("Sin actividad de login en el rango seleccionado") en vez de un error — probado tanto en el servicio como end-to-end (200 con todo en cero).
- **Detalle de diseño**: agregación hecha en Java (no SQL), consistente con el resto del codebase y con la decisión de "volumen bajo" de la fase de definición. Detalle completo en `docs/ARQUITECTURA.md` (sección "Ticket 016").
- 238/238 tests en verde (227 antes de este ticket + 11 nuevos).
