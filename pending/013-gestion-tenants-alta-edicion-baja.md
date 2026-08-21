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
