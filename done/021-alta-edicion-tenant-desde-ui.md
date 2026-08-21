# 021 — Alta y edición de tenant desde la UI

## Objetivo
Segundo ticket del rediseño de UI definido en `docs/definiciones/rediseno-ui-completo.md` (HU-4, HU-5). Hoy dar de alta o editar un tenant solo es posible vía API directa (curl/Postman) — este ticket agrega formularios reales en `/ui/admin/tenants`, sin backend nuevo (reutiliza `POST`/`PUT /api/v1/admin/tenants` ya existentes desde el ticket 013).

**Depende de:** ticket 020 (sistema visual + shell del panel) — construye sobre esa base visual, no antes.

## Criterios de aceptación (TDD)
- Formulario de alta en `/ui/admin/tenants` (solo visible/usable para `platform_admin`) — crea el tenant real vía el `POST` ya existente y aparece en la lista sin recargar la página.
- Nombre de tenant duplicado muestra el error 409 real con un mensaje claro (no un fallo silencioso ni un error genérico).
- Alguna forma de editar un tenant existente (appName/color/TTLs) desde la UI — usa el `PUT` ya existente, los cambios se reflejan de inmediato.
- Un `tenant_admin` editando un tenant que no es el suyo recibe 403 real, mostrado con claridad en la UI (no una pantalla accesible que falle en silencio).
- Prueba end-to-end real (sin mocks): alta + edición completas desde una request HTTP real, mismo nivel que el resto del panel admin.

## Hecho
- **Decisión resuelta con el Product Owner**: contradicción real entre HU-2 (ticket 019, ya cerrada) y HU-5 de este rediseño — edición desde esta UI queda efectivamente solo-platform_admin por ahora (tenant_admin sigue pudiendo editar su propio tenant vía API directa). Reabrir el ticket 019 queda fuera de alcance. Detalle en `docs/ARQUITECTURA.md`.
- Formulario de alta real en `/ui/admin/tenants` (mismo `POST` ya existente), TTLs precargados con los valores por defecto del proyecto.
- Edición vía `<dialog>` HTML real, precarga desde los datos ya en memoria, `PUT` al guardar.
- Ajuste visual encontrado en vivo: `input[type="color"]` corregido (heredaba padding de texto normal).
- Probado en vivo: alta y edición reales, sin recargar la página.
- Sin tests nuevos (sin backend nuevo — capa de presentación pura). 255/255 tests en verde.
