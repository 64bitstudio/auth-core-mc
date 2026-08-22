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
