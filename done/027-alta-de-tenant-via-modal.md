# 027 — Alta de tenant vía botón + modal

## Objetivo
Cuarto ticket de la fase 2 del rediseño de UI (`docs/definiciones/rediseno-ui-fase-2.md`, HU-5). El formulario de alta de tenant vive embebido al fondo de `/ui/admin/tenants` — se reemplaza por un botón "+ Añadir cliente" cerca del encabezado de la tabla que abre un modal, mismo patrón de `<dialog>` ya usado para editar desde el ticket 021.

**Depende de:** ticket 026 (layout/botones ya consistentes antes de agregar el modal).

## Criterios de aceptación (TDD)
- Dado que estoy en "Clientes", cuando veo la tabla, entonces hay un botón "+ Añadir cliente" visible cerca del encabezado, no al fondo de la página.
- Dado que hago clic en ese botón, cuando se abre, entonces aparece un `<dialog class="form-dialog">` con el mismo formulario de creación que existía antes (mismos campos, mismos defaults de TTL).
- Dado que cancelo el modal, cuando lo cierro, entonces no se crea ningún tenant (sin llamada al backend).
- Dado que envío el formulario del modal, cuando se crea el tenant, entonces la tabla se actualiza sin recargar la página y el modal se cierra.
- El formulario embebido actual al fondo de la página se elimina por completo.
- Reutiliza `POST /api/v1/admin/tenants` ya existente — sin cambios de backend.

## Hecho
- Botón "+ Añadir cliente" agregado junto al encabezado (`.section-header`), formulario embebido eliminado, reemplazado por `<dialog id="create-tenant-dialog" class="form-dialog">` (mismo patrón que `edit-tenant-dialog`).
- Form se resetea al abrir el modal (evita datos de un intento cancelado previo).
- Éxito: modal se cierra, tabla se recarga sin recargar la página, mensaje en `#status`.
- Ejecutado con delegación real al rol frontend-dev — topó el mismo límite de permisos que el ticket 026 (no fabricó sesión de admin) y correctamente no lo rodeó. Orquestador completó la verificación con sesión real: abrir/cancelar (sin llamada al backend) y crear un tenant real (modal cerrado solo, tabla actualizada).
- 260/260 tests en verde (sin tests nuevos). Detalle completo en `docs/ARQUITECTURA.md` (sección "Ticket 027").
