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
