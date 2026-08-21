# 013 — Gestión de tenants: alta, edición y baja con purga automática

## Objetivo
Endpoints y UI para dar de alta, editar y desactivar clientes (tenants) desde el panel. Nace de `docs/definiciones/panel-administracion-clientes.md` (HU-1, HU-5).

**Depende de:** tickets 011 (RBAC) y 012 (auth del panel) — estos endpoints deben estar gateados por rol desde el día uno.

## Criterios de aceptación (TDD)
- `POST/GET/PUT/DELETE /api/v1/admin/tenants` — solo accesible por `platform_admin` (alta/baja) o por el propio `tenant_admin` (edición de su tenant).
- Alta de tenant: nombre/slug único; slug duplicado responde error de conflicto claro.
- Edición: los cambios quedan reflejados de inmediato.
- Baja (desactivación): registra `deactivated_at`, bloquea nuevas sesiones de ese tenant, no borra datos de inmediato (soft delete).
- Job programado que purga físicamente los datos de tenants con `deactivated_at` hace más de 90 días — con test que verifique que NO purga antes de los 90 días y SÍ purga después.

## Hecho
- `POST/GET/PUT/DELETE /api/v1/admin/tenants[/{id}]` implementados (`AdminTenantController` + `AdminTenantService`). Gate grueso por ruta (ticket 012) + gate fino por tenant específico vía claims del JWT (`AdminAccessPolicy`, ya existente de los tickets 011/012, ahora reutilizado aquí) — sin consulta a base de datos para autorizar.
- Alta: nombre único (constraint real `UNIQUE` en BD + chequeo explícito en el servicio para un mensaje de error claro) — duplicado responde 409 (`DuplicateTenantNameException`). Solo `platform_admin`.
- Edición: `PUT` refleja los cambios de inmediato (`appName`, `primaryColor`, TTLs) — `name` es inmutable a propósito (ver nota de diseño abajo). `platform_admin` puede editar cualquier tenant; `tenant_admin` solo el suyo.
- Baja: `DELETE` marca `deactivated_at` (soft delete, solo `platform_admin`). `ClientContextResolver` rechaza con 403 cualquier request de un tenant desactivado — bloquea nuevas sesiones desde el único choke-point central de resolución de tenant/cliente.
- Job programado (`TenantPurgeService`, cron diario 03:00) purga físicamente los tenants desactivados hace ≥90 días, con tests reales que prueban ambos límites (no purga antes de 90 días, sí purga después) y el borrado completo en cascada manual (usuario, refresh token, cliente de identidad, proveedor de identidad, evento de login).
- **Nota de scope, decidida al implementar**: el objetivo original mencionaba "endpoints y UI", pero los Criterios de aceptación (TDD) de este ticket solo describen la API — no hay UI en los criterios. Se implementó únicamente la API, que es lo que este ticket define como "hecho" vía TDD. La UI de administración de tenants queda fuera de este ticket (no estaba en un HU con pantallas concretas en la definición para este alcance específico).
- **Sin campo `slug` separado**: se usó `name` con `UNIQUE` real como identificador estable, para no romper el constructor de 8 parámetros de `Tenant` usado en decenas de tests preexistentes. Detalle completo en `docs/ARQUITECTURA.md` (sección "Ticket 013").
- Prueba end-to-end real (`AdminTenantEndToEndTest`) cierra un hueco que el ticket 012 dejó explícitamente anotado como pendiente: la regla de rol `/api/v1/admin/**` solo se había probado genéricamente porque no existía ningún endpoint admin real todavía.
- 223/223 tests en verde (206 antes de este ticket + 17 nuevos).
