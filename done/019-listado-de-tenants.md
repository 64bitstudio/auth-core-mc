# 019 — Listado de todos los clientes (solo admin global)

## Objetivo
Pantalla en el panel de administración que lista todos los tenants — solo visible/accesible para `platform_admin`. Nace de una necesidad detectada al probar el panel en vivo: no había forma de descubrir qué tenants existen sin conocer su ID de antemano (anotado como fuera de alcance del ticket 013).

## Criterios de aceptación (TDD)
- `GET /api/v1/admin/tenants` — lista todos los tenants. Solo `platform_admin`; un `tenant_admin` recibe 403 (nunca debe poder enumerar tenants ajenos).
- UI en `/ui/admin/tenants` — tabla con nombre, app, estado (activo/desactivado), fecha de creación. Enlace directo a métricas de cada tenant (prellenando el campo Tenant) — **no** a "proveedores de login": esa página es deliberadamente solo-tenant-propio desde el ticket 014 (lee el tenant del JWT del caller, no acepta uno arbitrario); extenderla a cualquier tenant es un cambio de alcance real que no se pidió aquí, así que se deja fuera de este ticket.
- Sin paginación en esta versión (volumen bajo, decisión ya establecida para este proyecto — ver `docs/definiciones/panel-administracion-clientes.md`).

## Hecho
- `GET /api/v1/admin/tenants` — `AdminTenantService.list(actorRole)` exige `PLATFORM_ADMIN` explícitamente (no delega a `AdminAccessPolicy`, que responde una pregunta de autorización distinta: acceso a UN tenant, no a la lista completa).
- UI `/ui/admin/tenants`: tabla real (nombre, app, estado, fecha de creación) + enlace "Ver métricas" por fila, prellenando el tenant en `/ui/admin/metrics?tenant=<id>`.
- **Decisión de alcance**: sin enlace a "proveedores de login" (esa página es deliberadamente solo-tenant-propio desde el ticket 014; extenderla es un cambio de alcance real, no incluido aquí). Detalle en `docs/ARQUITECTURA.md` (sección "Ticket 019").
- Sin paginación (volumen bajo, decisión ya establecida para el proyecto).
- Probado end-to-end real y en vivo en el navegador: `platform_admin` ve todos los tenants (incluyendo uno desactivado); `tenant_admin` recibe 403 real, en API y en UI.
- 255/255 tests en verde (250 antes de este ticket + 5 nuevos).
