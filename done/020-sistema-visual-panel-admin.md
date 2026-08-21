# 020 — Sistema visual + layout compartido del panel de administración

## Objetivo
Primer ticket del rediseño de UI definido en `docs/definiciones/rediseno-ui-completo.md` (HU-1, HU-2). Construye la identidad visual propia del panel admin (paleta "Slate + Índigo") y un layout compartido con navegación real entre sus 3 pantallas — hoy cada página es una isla sin nav ni identidad propia.

**Depende de:** ninguno de los tickets de la épica de rediseño (es el primero). Sí depende del panel admin ya existente (tickets 011-019).

## Criterios de aceptación (TDD)
- `static/css/admin.css` nuevo, con los tokens de la paleta confirmada (fondo `#f8f9fb`, superficie `#ffffff`, borde `#e4e7ec`, texto `#101322`, muted `#667085`, acento `#4f46e5`/hover `#4338ca`, acento suave `#eeedfd`) — nunca lee `--primary-color`. `app.css` no se modifica.
- `templates/fragments/admin-shell.html` — fragmento Thymeleaf (`th:fragment`) con header + nav (Clientes/Métricas/Proveedores) + botón de cerrar sesión, incluido por las 3 páginas admin existentes en vez de que cada una repita su propio `<header>`.
- `api.js` gana `logout()` (limpia `sessionStorage`, redirige a `/ui/login`) y `currentRole()` (decodifica `role` del JWT, mismo patrón que `currentTenantId()` ya existente) — aditivas, sin romper ninguna página existente.
- El nav del shell solo muestra "Clientes" cuando `currentRole() === 'PLATFORM_ADMIN'` — probado con un `tenant_admin` real (no debe aparecer el link, aunque el 403 del backend ya lo protegía desde el ticket 019).
- Las 3 páginas admin existentes (`admin-tenants`, `admin-metrics`, `admin-identity-providers`) migran al nuevo shell + `admin.css` — todos los tests existentes de esas páginas (`UiPagesControllerTest`, los end-to-end) siguen en verde.
- Cerrar sesión probado end-to-end: limpia el token y redirige a login; una página admin visitada después sin sesión pide login de nuevo.

## Hecho
- `static/css/admin.css` nuevo — paleta "Slate + Índigo" confirmada, nunca lee `--primary-color`. `app.css` intacto.
- `templates/fragments/admin-shell.html` — 3 fragmentos (`topbar`, `sidenav(active)`, `script`), no uno solo (necesario por cómo se anida el layout flex).
- `api.js` gana `logout()` y `currentRole()`.
- **Bug real encontrado en vivo** (no por tests automatizados): `if (window.AuthCoreUi)` como guard era siempre falso — un `const` de nivel superior en un script clásico no se vuelve propiedad de `window`. Arreglado con `typeof AuthCoreUi !== "undefined"`. Detalle completo en `docs/ARQUITECTURA.md` (sección "Ticket 020").
- Las 3 páginas admin migradas al shell/estilos nuevos; de paso arreglado el bug cosmético del texto "cargando…" pegado en proveedores de login (ya anotado desde el ticket 014).
- 3 tests de `UiPagesControllerTest` actualizados para afirmar explícitamente que el panel YA NO se tematiza por tenant.
- Probado en vivo con los 3 roles reales: nav con resaltado correcto, "Clientes" solo para platform_admin, logout funcionando.
- 255/255 tests en verde.
