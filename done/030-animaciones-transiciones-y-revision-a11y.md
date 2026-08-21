# 030 — Animaciones/transiciones + revisión final de accesibilidad

## Objetivo
Séptimo y último ticket de la fase 2 del rediseño de UI (`docs/definiciones/rediseno-ui-fase-2.md`, HU-8). Cierra el epic con transiciones sutiles en modales/hover/focus en todo el panel, respetando `prefers-reduced-motion`, y una revisión final de accesibilidad sobre todo lo agregado en 024-029 (íconos, gráficas, nuevas pantallas).

**Depende de:** tickets 024-029 (toca todas las superficies que agregaron).

## Criterios de aceptación (TDD)
- Todo `<dialog>` del panel (confirm-dialog, form-dialog, el nuevo modal de alta de tenant del ticket 027) tiene una transición de entrada breve (fade/scale), no un aparecer instantáneo.
- Hover/focus de botones y links del panel tiene una transición suave y consistente (revisar que no quedó ninguna interacción brusca de los tickets anteriores).
- Dado `prefers-reduced-motion: reduce`, cuando interactúo con cualquier elemento animado del panel, entonces las animaciones se desactivan o se reducen a un cambio instantáneo — mismo criterio ya aplicado a los spinners del ticket 023.
- Revisión de accesibilidad sobre lo nuevo de 024-029: `aria-hidden` correcto en íconos decorativos, texto alternativo en los que transmiten información (ej. estado de un proveedor), gráficas SVG del ticket 028 con una alternativa textual para lectores de pantalla (no solo visual).
- Sin regresión en ningún test existente de `UiPagesControllerTest` ni en los tests de los tickets 020-029.

## Hecho
- Entrada de `<dialog>` con `@starting-style` (técnica nativa correcta para `showModal()`, no `@keyframes`) — cubre `confirm-dialog`/`form-dialog` y por tanto el modal de alta de tenant del ticket 027.
- Hover/focus: encontrados y corregidos 2 casos sin transición (`.admin-sidenav a` no tenía ninguna; `button` base no cubría `color`/`border-color`/`box-shadow`).
- `prefers-reduced-motion` aplicado a todo lo animado por este ticket.
- 2 hallazgos reales de accesibilidad corregidos: porcentaje de éxito de la dona agregado como texto real (antes solo en el SVG decorativo); `<label for="...">` sobre un `<p>` (inválido) reemplazado por `aria-labelledby`. El resto de la revisión (14 íconos, gráfica de barras, estados de proveedor) se confirmó ya correcto.
- Ejecutado con delegación real al rol frontend-dev — topó el límite de permisos de siempre, verificó con `getAnimations()`/`getComputedStyle` sobre un harness estático. Orquestador completó la verificación con sesión real: animación capturada a mitad de transición, hover del sidenav confirmado, porcentaje de éxito confirmado exacto contra el centro de la dona (87%).
- 260/260 tests en verde (sin tests nuevos). Detalle completo en `docs/ARQUITECTURA.md` (sección "Ticket 030"). **Cierra el epic completo de la fase 2 del rediseño de UI (024-030).**
