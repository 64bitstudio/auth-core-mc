# 023 — Rediseño de las páginas de usuario final

## Objetivo
Cuarto y último ticket del rediseño de UI definido en `docs/definiciones/rediseno-ui-completo.md` (HU-3). Mejora visual/estructural de las 7 páginas de usuario final (registro, login, cuenta, password-reset request/confirm, verify-email confirm, change-email confirm) — tipografía, jerarquía, espaciado, estados de carga/vacío. El theming por tenant (`--primary-color`) se mantiene intacto, es su propósito y no un problema a resolver.

**Depende de:** ninguno de los tickets 020-022 (sistema independiente, sin relación técnica con el panel admin) — va al final solo por prioridad acordada con el Product Owner, no por dependencia real.

## Criterios de aceptación (TDD)
- El theming `--primary-color` configurado por cada tenant sigue funcionando sin regresión en las 7 páginas (probado con los tests existentes de `UiPagesControllerTest`).
- Mejora real y consistente de tipografía/espaciado/jerarquía visual en las 7 páginas, no solo en algunas.
- Estados de carga y vacío mejor manejados (ej. mientras se envía un formulario, cuando no hay nada que mostrar) — si esto requiere cambios de HTML/JS y no solo CSS, documentarlo explícitamente en `## Hecho`, no asumir que fue CSS-only sin verificarlo.
- Cero cambios a la lógica de negocio de estas páginas (mismo patrón que el rediseño visual del ticket 010 — HTML/CSS/JS de presentación, sin tocar `api.js` en su contrato con el backend).

## Hecho
- `AuthCoreUi.withBusy(button, task)` nuevo en `api.js` (aditivo, sin tocar el contrato de ninguna función existente con el backend): deshabilita el botón y muestra un spinner CSS mientras `task` corre, restaura siempre en `finally`.
- Aplicado a los 11 puntos de envío async de `register`, `login`, `password-reset/request`, `password-reset/confirm` y `cuenta` (7 acciones: reenviar verificación + 6 formularios de 2FA).
- Las páginas de confirmación por token (`verify-email/confirm`, `change-email/confirm`) muestran el mismo spinner desde el HTML servido, sin JS adicional — `showStatus()` lo limpia junto con el mensaje final.
- Bug real encontrado en vivo: `section.card` no tenía espacio entre `<form>` hermanos (la sección 2FA de `cuenta.html` los tenía pegados) — corregido con `display:flex; flex-direction:column; gap`.
- Los 3 sub-flujos de 2FA (antes separados solo por `<hr/>`) ahora tienen encabezados `<h4>` reales dentro de un wrapper `.subsection` — jerarquía real, no solo espaciado.
- Estados vacíos revisados explícitamente en las 7 páginas: ninguna tiene contenido tipo lista/colección, no aplica un estado nuevo (documentado, no asumido en silencio).
- Theming `--primary-color` por tenant sin regresión — probado por los tests existentes de `UiPagesControllerTest` y confirmado en vivo.
- Probado en vivo: spinner de botón, spinner de páginas de confirmación, layout de 2FA, y el camino de error (500 real por falta de `RESEND_API_KEY` en este entorno, limitación preexistente no relacionada) confirmando que el estado de carga se limpia también al fallar.
- 259/259 tests en verde (sin tests nuevos, capa de presentación pura). Detalle completo en `docs/ARQUITECTURA.md` (sección "Ticket 023").
- Cierra el epic de rediseño de UI completo (`docs/definiciones/rediseno-ui-completo.md`, tickets 020-023).
