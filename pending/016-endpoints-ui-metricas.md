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
