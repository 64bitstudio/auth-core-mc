# 028 — Métricas gráficas

## Objetivo
Quinto ticket de la fase 2 del rediseño de UI (`docs/definiciones/rediseno-ui-fase-2.md`, HU-6). `/ui/admin/metrics` pide el tenant como UUID crudo en un input de texto y muestra los resultados en líneas de texto plano — se reemplaza por un selector real, accesos rápidos de rango, y los resultados se muestran con tarjetas de estadística + gráficas SVG.

**Depende de:** ticket 024 (fragmento de íconos).

## Criterios de aceptación (TDD)
- El selector de tenant deja de ser un input de texto libre: `platform_admin` ve un `<select>` real poblado desde `GET /api/v1/admin/tenants` (ya existente); `tenant_admin` no ve selector — su propio tenant (leído del JWT) queda fijo como texto.
- El selector de rango de fechas incluye accesos rápidos (7/30/90 días) además de los date pickers ya existentes.
- Tarjetas de estadística con ícono para: logins totales, usuarios activos, usuarios registrados, latencia promedio.
- Gráfica SVG (barra o dona) comparando éxitos vs. fallos — reemplaza el texto "Éxitos: X — Fallos: Y".
- Gráfica de barras SVG por proveedor (`byProvider`), cuando hay más de un proveedor con actividad.
- Un rango sin actividad sigue siendo un estado vacío válido (200, todo en cero) — con su propio tratamiento visual, no un error.
- `static/js/charts.js` nuevo: funciones puras dato → SVG, sin librería externa (política de cero dependencias).
- Sin cambios de backend — `TenantMetricsResponse`/`AdminMetricsService` no se tocan (confirmado que no exponen serie temporal; una gráfica de tendencia queda fuera de este ticket, ver el documento de definición).

## Hecho
- Selector de tenant: `<select>` real para `platform_admin` (poblado desde `GET /api/v1/admin/tenants`); nombre fijo de solo lectura para `tenant_admin` (vía `GET /api/v1/admin/tenants/{id}` sobre su propio id, ya permitido, no una capacidad nueva).
- Accesos rápidos 7/30/90 días como chips que rellenan los date pickers y disparan la consulta.
- `static/js/charts.js` nuevo: dona SVG para éxito/fallo (proporción de un todo), barras horizontales SVG para actividad por proveedor (etiquetas de longitud variable) — cero dependencias, texto escapado antes de interpolar.
- Con un solo proveedor activo se muestra una frase en vez de una "comparación" de un elemento.
- Tarjetas de estadística con ícono (logins totales, usuarios activos, registrados, latencia).
- Estado vacío reutiliza la ilustración `illus-vacio` (ticket 024, nunca usada hasta ahora) + su animación ya existente.
- Ejecutado con delegación real al rol frontend-dev, en worktree propio en paralelo con el ticket 029. Topó el mismo límite de permisos de siempre (no fabricó sesión de admin).
- **Bug real encontrado en vivo por el orquestador** al completar la verificación: el estado vacío se renderizaba en bloque apilado, sin centrar — corregido agregando `display:flex; flex-direction:column; align-items:center` a `.empty-state`.
- Verificado en vivo con datos reales (tenant con 43 logins acumulados): dona con 86% de éxito, tarjetas con números reales, caso de un proveedor, estado vacío ya centrado, vista de `tenant_admin` con nombre fijo.
- 260/260 tests en verde (sin tests nuevos). Detalle completo en `docs/ARQUITECTURA.md` (sección "Ticket 028").
