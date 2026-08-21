# 011 — RBAC: roles de plataforma y de cliente

## Objetivo
Introducir el modelo de autorización para el panel de administración de clientes: roles `platform_admin` (acceso total) y `tenant_admin` (acceso solo a su propio tenant). Es la base de la que dependen los demás tickets del panel (012-016). Nace de la fase de definición documentada en `docs/definiciones/panel-administracion-clientes.md` (HU-3).

## Criterios de aceptación (TDD)
- Columna `role` en `app_user` (ENUM: PLATFORM_ADMIN, TENANT_ADMIN) — decisión de modelo tomada en la definición (no tabla de roles aparte, dado que `app_user` ya está 1:1 scoped a un tenant).
- Un usuario con rol `platform_admin` puede operar sobre cualquier tenant.
- Un usuario con rol `tenant_admin` solo puede operar sobre su propio tenant — un intento de acceder a otro tenant (por API) responde 403, no una fuga de datos.
- Tests que verifiquen el aislamiento (mismo patrón que `TenantIsolationTest` del ticket 008).

## Hecho (TDD real: rojo → verde)
- Columna `role` en `app_user` (`NONE`/`TENANT_ADMIN`/`PLATFORM_ADMIN`, `TEXT`+`CHECK`, default `NONE`) — migración `V3__admin_panel_role.sql`, mismo patrón que `two_factor_method` (ticket 005).
- `AdminAccessPolicy` (paquete `security`) — lógica de decisión pura: `platform_admin` accede a cualquier tenant, `tenant_admin` solo al suyo (comparado por `id`, no por igualdad de objeto), cualquier otro rol no accede administrativamente a ninguno.
- `User.grantRole()`/`getRole()` — nuevos, siguiendo el estilo de mutación explícita ya usado en la clase (`markEmailVerified()`, `activateTwoFactorMethod()`, etc.).
- `AdminAccessPolicyTest` — 4 tests nuevos, con filas reales persistidas (mismo patrón que `TenantIsolationTest`, ticket 008), no objetos armados a mano: platform_admin accede a cualquier tenant, tenant_admin solo al suyo, un usuario sin rol admin no accede a ninguno, un usuario nuevo por defecto tiene rol `NONE`.
- `docs/ARQUITECTURA.md` actualizado con la sección del ticket 011.
- 187/187 tests en verde (183 previos + 4 nuevos).
- Deliberadamente fuera de este ticket: el guard/interceptor que conecta esta política al pipeline real de requests HTTP — eso es el ticket 012.
