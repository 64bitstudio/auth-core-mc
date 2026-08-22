# 034 — Homologar convención de clase en mensajes de éxito/error (`[role="status"]`/`[role="alert"]`)

## Objetivo
Hallazgo encontrado durante la verificación en vivo del ticket 032: `AuthCoreUi.showStatus()` (`api.js`) agrega la clase `success` al contenedor de estado cuando el mensaje es de éxito (`class="success"`), pero cuando es un error el contenedor queda con `class=""` — el estilo rojo de error se aplica vía el selector `[role="alert"]` en CSS, no por una clase equivalente. Es solo una inconsistencia de convención (ambos casos ya funcionan visualmente bien, `role` distingue éxito/error correctamente), pero conviene homologar para que el patrón sea uno solo y no dos mecanismos distintos para el mismo propósito.

**No incluye:** ningún cambio visual — el resultado final (color, layout) debe verse idéntico al actual en ambos casos; es una limpieza de convención interna, no una feature.

## Criterios de aceptación (TDD)
- Se define una única convención (ej. agregar clase `success`/`error` explícita en ambos casos, o eliminar la clase `success` y depender solo de `role` + los selectores CSS ya existentes — decidir cuál según lo que ensucie menos `showStatus()` y `admin.css`/`app.css`) y se aplica de forma consistente en `AuthCoreUi.showStatus()`.
- CSS de `admin.css` y `app.css` para `[role="status"]`/`[role="alert"]` ajustado si el mecanismo elegido lo requiere, sin cambiar el resultado visual actual (mismo color, mismo layout, mismo ícono del ticket 032).
- Verificado en vivo: un mensaje de éxito y uno de error en al menos 2 páginas distintas (ej. `admin-tenants` y `login`) se ven visualmente idénticos a como se veían antes de este ticket.
- Tests existentes siguen en verde; no se requieren tests nuevos si el cambio es puramente de consistencia interna sin lógica nueva.

## Hecho
- Mecanismo elegido: **clase explícita simétrica** (`success`/`error`) en ambos casos, en vez de depender de `role` para el color del error. Razón: `role="status"` ya se comparte con el estado de carga (`is-loading`, spinner de `verify-email-confirm.html`/`change-email-confirm.html`) — depender solo de `role` no habría permitido distinguir en CSS "cargando" (sin color) de "éxito" (verde) sin tocar además esas plantillas, un blast radius mayor al que amerita esta limpieza. Con clase explícita en ambos casos, `role` queda puramente para semántica ARIA y la clase determina el color — un solo mecanismo, simétrico.
- `showStatus()` (`api.js`): ahora limpia y agrega `error`/`success` de forma simétrica (`el.classList.add(isError ? "error" : "success")`), en vez de solo agregar `success` cuando no hay error.
- `admin.css` / `app.css`: selector de color de error cambiado de `[role="alert"]` a `[role="alert"].error` — la regla base de `[role="alert"]` (layout/ícono, compartida con `[role="status"]`) queda intacta.
- Confirmado que ningún template tiene `role="alert"` hardcodeado (solo lo pone `showStatus()`) — gatear el color por `.error` no puede romper ningún otro caso.
- 260/260 tests en verde, sin cambios de comportamiento backend.
- Verificado en vivo (Chrome): `login` (error de credenciales inválidas, rojo + ícono, `class="error" role="alert"` confirmado en DOM), `password-reset-request` (éxito genérico, verde + ícono, `class="success" role="status"`), `register` (error de validación 400, rojo). **No se pudo verificar en `admin-tenants`/páginas admin** por falta de credenciales `platform_admin` — mismo archivo CSS/JS compartido por las 11 páginas, el resultado debería ser idéntico, pero no se vio con los propios ojos en esa pantalla específica; se deja explícito en vez de asumirlo.
- Sin cambio de documentación necesario — ningún doc de `/docs` describe este mecanismo interno de clases.
- Detalle completo en `docs/ARQUITECTURA.md` (sección "Ticket 034").
