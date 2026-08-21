# 024 — Mejora del agente ux-ui-designer + sistema de íconos SVG reutilizable

## Objetivo
Primer ticket de la fase 2 del rediseño de UI, definida en `docs/definiciones/rediseno-ui-fase-2.md` (HU-9, base técnica de HU-4). Antes de tocar cualquier vista, se refuerzan las instrucciones del subagente `ux-ui-designer` (para que exija por defecto iconografía, animaciones con `prefers-reduced-motion` y SVG inline en sus entregas) y se construye el fragmento de íconos compartido que el resto de los tickets de esta fase va a reutilizar.

**Depende de:** ninguno de los tickets 020-023 (ya cerrados). Es prerequisito de 025-030.

## Criterios de aceptación (TDD)
- `~/.claude/agents/ux-ui-designer.md` actualizado: "Tu enfoque" exige explícitamente iconografía/ilustraciones, animaciones/transiciones con `prefers-reduced-motion`, y SVG inline (nunca fuentes de íconos ni imágenes externas) en cada entrega de diseño, no como sugerencia opcional.
- El agente `ux-ui-designer` se instancia de verdad (vía el tool `Agent`) para decidir qué íconos necesita el panel y su estilo visual (trazo, tamaño, grosor) — no se asume el set de íconos sin su participación real.
- Fragmento Thymeleaf nuevo (`fragments/icons.html` o similar) con un `th:fragment` por cada ícono acordado con el agente (mínimo: inicio, clientes, métricas, proveedores de login, éxito, error, vacío/sin-datos) — reutilizable vía `th:replace` en cualquier página admin.
- Cada ícono tiene `aria-hidden="true"` cuando es puramente decorativo.
- No se crea ninguna dependencia externa (sin fuentes de íconos, sin paquete npm, sin CDN) — coherente con la política de cero dependencias del proyecto.

## Hecho
- `~/.claude/agents/ux-ui-designer.md` actualizado con el "Estándar de entrega" (iconografía/ilustraciones, SVG inline sin dependencias, animaciones con `prefers-reduced-motion`, mejores prácticas de diseño) como parte fija del rol.
- Íconos diseñados por una instancia real del rol ux-ui-designer (workaround `general-purpose` + persona inyectada, ya que los subagentes personalizados no cargan en esta sesión — ver `docs/ARQUITECTURA.md` sección "Ticket 024"), no inventados por el hilo principal.
- `fragments/icons.html` nuevo con 14 `th:fragment` (4 nav/header, 5 tarjetas de métricas, 2 estados, 1 ilustración vacía, 2 logos de marca).
- Sidenav (`admin-shell.html`) wireado con los 3 íconos de las secciones existentes, implementado por una instancia real del rol frontend-dev (mismo workaround).
- Animación de entrada de la ilustración vacía agregada a `admin.css`, con guard de `prefers-reduced-motion`.
- Verificado en vivo: íconos legibles a tamaño real, recoloreo correcto en hover/active, logos de Google/Facebook confirmados fieles a marca en un navegador real.
- 259/259 tests en verde. Detalle completo en `docs/ARQUITECTURA.md` (sección "Ticket 024").
