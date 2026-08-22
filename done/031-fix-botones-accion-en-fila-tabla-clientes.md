# 031 — Botones de acción en fila (icono + texto) en la tabla de clientes

## Objetivo
En `/uid/admin/tenants`, los botones "Editar" y "Desactivar"/"Reactivar" de cada fila se ven desbordados/apilados — resultado del fix del ticket 022 (`.actions-cell-inner` en columna) que evitó que el texto se truncara pero dejó la celda visualmente incómoda. Se rediseñan como botones compactos (icono + texto corto) que caben en fila sin volver a empujar la tabla más ancha que la tarjeta.

**Depende de:** ticket 022 (`.actions-cell-inner`, el layout que se reemplaza) y 024 (sistema de íconos SVG compartido, `fragments/icons.html`). No depende del 032.

## Criterios de aceptación (TDD)
- `fragments/icons.html` gana los íconos nuevos que falten para estas acciones (ej. editar/lápiz, dar de baja, reactivar) — mismas convenciones ya establecidas (`viewBox 0 0 24 24`, `stroke="currentColor"`, `aria-hidden="true"`).
- Texto de los botones se acorta ("Editar", "Baja", "Alta" o equivalente) para que quepan lado a lado sin truncarse ni forzar scroll horizontal en el ancho de tarjeta estándar del panel (~1280px de viewport, breakpoints existentes del panel).
- `.actions-cell-inner` pasa de columna a fila (`flex-direction: row`) — verificar en vivo con una lista de varios tenants (activos e inactivos mezclados) que ninguna fila desborda la tarjeta ni trunca texto, replicando el escenario que motivó el fix original del ticket 022.
- El texto completo de la acción sigue siendo accesible (visible o vía `aria-label` si se abrevia demasiado) — no solo icono sin equivalente textual.
- Tests existentes de `admin-tenants` (`UiPagesControllerTest`, end-to-end si aplica) siguen en verde; no se requieren tests nuevos si el cambio es puramente visual sin lógica nueva.

## Hecho
- `fragments/icons.html`: 3 íconos nuevos (`icon-editar`, `icon-desactivar`, `icon-reactivar`), mismas convenciones del ticket 024. Inyectados en `admin-tenants.html` vía `<template>` + `th:replace` (las filas se arman en JS, no server-side por fila) — el JS los lee una vez como HTML string reutilizable.
- Botones "Editar" / "Baja" / "Alta" en fila, icono + texto corto, con `aria-label` completo por fila (ej. "Desactivar Acme") para no perder desambiguación al abreviar el texto visible.
- `.actions-cell-inner` vuelve a `flex-direction: row`; comentario del ticket 022 actualizado explicando por qué ahora sí es seguro (etiquetas cortas + icono, no revertir el fix a ciegas).
- **Hallazgo real de layout** (no se hubiera visto sin medir en vivo): el primer diseño de `.icon-btn` sí desbordaba (~50px) contra la lista real de tenants del entorno, que incluye nombres largos heredados de tickets anteriores (`TicketVerify027`). Causa: `.admin-content` tiene `max-width: 900px` fijo. Corregido con `.actions-cell` (padding propio, más angosto que el `td` compartido) + afinar `.icon-btn`, iterado en vivo hasta 0px de desborde exacto.
- **Hallazgo real de seguridad, corregido de paso:** `escapeHtml()` no escapaba comillas; al reutilizarse para construir `aria-label` (atributo, no solo texto de nodo), un nombre de tenant con `"` podía romper el HTML generado. Extendida para escapar `"`/`'` también.
- Verificado en vivo con sesión `platform_admin` real (`qa-visual-031@example.com`, promovido con `UPDATE` puntual autorizado explícitamente por el Product Owner — se deja como `PLATFORM_ADMIN` reutilizable en la BD local de desarrollo, decisión también explícita del Product Owner, para no repetir este obstáculo en tickets futuros): tabla con tenants activos e inactivos mezclados, sin desborde ni texto truncado, colores de ícono correctos vía `currentColor`.
- 260/260 tests en verde (sin tests nuevos).
- QA automático del PR (#36): sin hallazgos.
- **Mejora continua propuesta, no implementada (fuera de alcance):** el servidor de dev cachea templates de Thymeleaf en memoria y `bootRun` no recopia recursos estáticos en caliente — forzó reinicios manuales para iterar. Resuelto después en el ticket 033 (`spring-boot-devtools`).
- Detalle completo en `docs/ARQUITECTURA.md` (sección "Ticket 031").
