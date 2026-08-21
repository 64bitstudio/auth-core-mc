# 026 — Layout centrado y convención de botones sin desborde

## Objetivo
Tercer ticket de la fase 2 del rediseño de UI (`docs/definiciones/rediseno-ui-fase-2.md`, HU-3). El contenido del panel no está centrado en viewports anchos (le falta `margin: 0 auto` a `.admin-content`) y hay botones que se desbordan o son inconsistentes entre sí (confirmado en vivo en `/ui/admin/tenants` y en "Proveedores de login").

**Depende de:** ticket 025 (agrega la nueva pantalla de inicio, que también debe quedar centrada).

## Criterios de aceptación (TDD)
- Dado un viewport ancho (1400px+), cuando veo cualquiera de las 4 páginas del panel (Clientes, Métricas, Proveedores, Inicio), entonces el área de contenido queda centrada en el espacio disponible junto al sidenav, no pegada a su borde izquierdo.
- Dado `/ui/admin/tenants`, cuando veo los botones Editar/Desactivar/Reactivar, entonces ninguno se corta ni se desborda de su celda — verificado en vivo, no solo revisando el CSS.
- Se define en `admin.css` una convención única de tamaño de botón (`.button-group` o similar): acción única de un formulario → ancho del contenedor; grupo de botones → mismo ancho entre sí, nunca mezclado full-width con auto-width en el mismo grupo.
- Verificado en vivo en al menos 2 anchos de viewport distintos.

## Hecho
- `.admin-content` gana `margin: 0 auto` — confirmado centrado computacionalmente en vivo (`margin-left`/`margin-right` = 185.5px exactos en un viewport real de 1471px, sesión autenticada real).
- Nueva convención `.button-group` en `admin.css`, aplicada al grupo Guardar/Desactivar de cada tarjeta de proveedor en `admin-identity-providers.html` (antes full-width vs auto-width, ahora mismo ancho) — confirmado en vivo.
- `admin-tenants.html`, `admin-metrics.html`, `admin-home.html` revisados explícitamente, sin otro desborde/inconsistencia encontrado.
- Verificado en 2 anchos de viewport reales: 700px (breakpoint móvil de 720px sigue funcionando, sin regresión) y 1471px (centrado confirmado).
- Ejecutado con delegación real al rol frontend-dev — encontró un límite real de permisos al intentar fabricar una sesión de admin (bloqueado correctamente, no lo rodeó) y usó previews estáticas; el orquestador repitió la verificación con una sesión real autenticada.
- 260/260 tests en verde (sin tests nuevos, cambio puramente CSS/HTML). Detalle completo en `docs/ARQUITECTURA.md` (sección "Ticket 026").
