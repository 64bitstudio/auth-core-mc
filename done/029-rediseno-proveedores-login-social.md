# 029 — Rediseño de proveedores de login social

## Objetivo
Sexto ticket de la fase 2 del rediseño de UI (`docs/definiciones/rediseno-ui-fase-2.md`, HU-7). `/ui/admin/identity-providers` no tiene identidad de marca (solo texto "Google"/"Facebook") y sus botones son inconsistentes (Guardar full-width, Desactivar auto-width, confirmado en vivo).

**Depende de:** ticket 026 (convención de botones ya definida).

## Criterios de aceptación (TDD)
- Cada tarjeta de proveedor (Google, Facebook) muestra el logo de marca correspondiente junto al nombre, vía el fragmento de íconos del ticket 024 o un ícono propio de marca si el agente ux-ui-designer lo decide así.
- El grupo de botones Guardar/Desactivar de cada tarjeta sigue la convención de tamaño del ticket 026 (mismo ancho entre sí).
- El formulario puede reorganizarse (ej. layout de 2 columnas ya usado en el modal de edición de tenant) si mejora la jerarquía visual — decisión del agente ux-ui-designer, no asumida.
- Apple sigue mostrándose como no disponible (mensaje ya existente), sin card propia — sin cambio de alcance ahí.
- Sin cambios de backend — reutiliza `GET`/`PUT`/`DELETE /api/v1/admin/identity-providers/*` ya existentes.

## Hecho
- Logos `logo-google`/`logo-facebook` (ticket 024) agregados junto al `<h3>` de cada tarjeta, nuevo wrapper `.card-title`.
- Convención `.button-group` del ticket 026 confirmada intacta, sin cambios necesarios.
- Layout de 2 columnas evaluado y descartado explícitamente (documentado en el HTML): cada tarjeta solo tiene 2 campos, ambos credenciales opacas largas — partirlas a la mitad no ahorra espacio y dificulta leer/pegar el valor.
- Ejecutado con delegación real al rol frontend-dev, en worktree propio en paralelo con el ticket 028. Topó el mismo límite de permisos ya documentado (no fabricó sesión de admin), probó varias rutas no destructivas para verificar visualmente (todas bloqueadas por el entorno) y no insistió rodeándolas.
- Rama rebaseada sobre `main` actualizado (se había creado antes de que el ticket 027 mergeara) por el orquestador.
- 260/260 tests en verde (sin tests nuevos). Detalle completo en `docs/ARQUITECTURA.md` (sección "Ticket 029").
