# 032 — Iconografía e ilustraciones: resto del panel admin y páginas de usuario final

## Objetivo
El ticket 024 construyó el sistema de íconos SVG compartido (`fragments/icons.html`) y lo aplicó parcialmente (sidenav, tarjetas de métricas, estado vacío de clientes). Este ticket completa la cobertura: audita qué vistas del panel admin y qué páginas de usuario final (rediseñadas visualmente en el ticket 023 pero sin iconografía/ilustraciones) necesitan íconos o ilustraciones nuevas, y las agrega — reutilizando el set existente donde aplique y ampliándolo solo donde falte.

**Alcance:**
- Panel admin: `admin-tenants`, `admin-metrics`, `admin-identity-providers`, `admin-home`.
- Páginas de usuario final: `login`, `register`, `cuenta`, `password-reset-request`, `password-reset-confirm`, `verify-email-confirm`, `change-email-confirm`.

**No incluye:** el fix de layout de los botones de acción de la tabla de clientes (eso es el ticket 031, que sí agrega 2-3 íconos de acción como parte de su propio alcance).

**Agregado tras VoBo explícito del Product Owner (2026-08-21), a partir de dos hallazgos del ux-ui-designer durante el diseño:**
- El estado vacío de `admin-tenants` ("No hay clientes registrados todavía") nunca quedó wireado con `illus-vacio` pese a que el comentario del ticket 024 lo daba por hecho — se corrige aquí. Sequenciado a propósito: se wirea solo después de que el ticket 031 (que toca el mismo archivo, `admin-tenants.html`) haya mergeado, para evitar un conflicto de merge garantizado entre ambas ramas.
- `AuthCoreUi.showStatus()` (helper compartido en `api.js`, usado por las 11 páginas listadas arriba) gana ícono `icon-exito`/`icon-error` automático junto al mensaje, en vez de wirearlo página por página.

**Depende de:** ticket 024 (sistema de íconos base) y 023 (rediseño visual de páginas de usuario final, ya cerrado).

## Criterios de aceptación (TDD)
- Se instancia una sesión real del agente `ux-ui-designer` (mismo patrón documentado en el ticket 024) para decidir, vista por vista, qué ícono o ilustración hace falta y con qué estilo — coherente con el set ya existente (mismo `viewBox`, `stroke-width`, convención de color) y sin inventar un estilo nuevo sin su participación.
- `fragments/icons.html` gana solo los íconos/ilustraciones nuevos que el agente determine necesarios (ej. estados vacíos por vista que no sean la tabla de clientes, iconografía de formularios de auth) — ningún ícono se agrega sin haber pasado por el rol de diseño.
- Cero dependencias externas nuevas (sin fuentes de íconos, sin paquete npm, sin CDN) — mismo criterio que el ticket 024.
- Cada ícono puramente decorativo lleva `aria-hidden="true"`; ningún ícono reemplaza texto sin equivalente accesible.
- Animaciones de ilustraciones (si las hay) respetan `prefers-reduced-motion`, igual que `illus-vacio` desde el ticket 024/030.
- Verificado en vivo en un navegador real: íconos legibles a tamaño real, recoloreo correcto en hover/focus donde aplique, sin romper el layout responsivo existente de ninguna de las vistas listadas.
- Tests existentes de todas las páginas tocadas siguen en verde; no se esperan tests nuevos si el cambio es puramente visual.
- `AuthCoreUi.showStatus()` en `api.js` inyecta `icon-exito`/`icon-error` junto al mensaje según `isError`; verificado que ninguna de las 11 páginas que lo usan rompe su layout de `[role="status"]`/`[role="alert"]` con el ícono nuevo.
- Estado vacío de `admin-tenants` (`#empty-state`) wireado con `illus-vacio` (mismo patrón que el estado vacío de `admin-metrics`, ticket 028) — solo después de confirmar que el ticket 031 ya mergeó a `main` y rebasear esta rama sobre ese `main` actualizado.

## Hecho
- 11 fragmentos SVG nuevos en `fragments/icons.html` (`icon-crear-cuenta`, `icon-mi-cuenta`, `icon-panel-admin`, `icon-verificacion-correo`, `icon-cambiar-correo`, `icon-correo`, `icon-telefono`, `icon-password`, `icon-2fa`, `icon-restablecer-password`, `illus-sin-acceso`) — diseñados por una instancia real del rol `ux-ui-designer` vista por vista, antes de wirear nada, documentados en un comentario en el propio `icons.html`.
- Headers con ícono en `admin-home`, `admin-metrics`, `admin-identity-providers`, `admin-tenants` (reusan los íconos de nav ya existentes) y las 7 páginas de usuario final (íconos nuevos + `icon-logins-totales` reusado en `login.html`).
- Íconos de campo junto al `<label>` (correo/teléfono/contraseña) en `login`, `register`, `cuenta`, `password-reset-request`, `password-reset-confirm`.
- `illus-sin-acceso` wireada en el estado bloqueado `#no-access-message` de `admin-home.html`.
- `AuthCoreUi.showStatus()` (`api.js`) antepone `icon-exito`/`icon-error` al mensaje vía un `<template id="status-icons">` por página — no toca ninguno de los ~20 call-sites existentes, degrada con gracia en páginas sin ese template.
- **Gap real cerrado, encontrado por el `ux-ui-designer` durante el diseño (no en el alcance original del ticket, agregado con VoBo explícito del Product Owner):** el estado vacío de `admin-tenants` (`#empty-state`) nunca había quedado wireado con `illus-vacio` pese a que el comentario del ticket 024 lo daba por hecho — era solo texto plano. Cerrado con el mismo patrón que `admin-metrics` (ticket 028), junto con el header e ícono de `showStatus()` que a esa página también le faltaban. Secuenciado a propósito: hecho solo después de que el ticket 031 (que tocaba el mismo archivo) mergeó a `main`, tras rebasear esta rama.
- **Decisión de alcance explícita, no implementada a propósito:** logos de proveedor (Google/Facebook) en la leyenda de `admin-metrics` — descartado por el `ux-ui-designer` porque `byProvider` incluye "PASSWORD" (sin logo propio) y exigiría lógica de fallback en `charts.js`, fuera del alcance de un cambio puramente de iconografía.
- 260/260 tests en verde (sin tests nuevos — cambio de presentación puro).
- Verificado en vivo (Chrome, sesión `platform_admin` real): las 9 páginas que llaman a `showStatus()` (excluyendo `admin-home`, que no lo usa), más el estado bloqueado de `admin-home` y el header/empty-state/mensajes de `admin-tenants` tras el rebase — íconos correctos, colores vía `currentColor`, árbol de accesibilidad limpio (`aria-hidden` en cada ícono decorativo, ningún `<label>` pierde su texto accesible).
- Detalle completo en `docs/ARQUITECTURA.md` (sección "Ticket 032").
