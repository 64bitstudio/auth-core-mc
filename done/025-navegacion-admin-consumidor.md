# 025 — Navegación admin↔consumidor

## Objetivo
Segundo ticket de la fase 2 del rediseño de UI (`docs/definiciones/rediseno-ui-fase-2.md`, HU-1, HU-2). Después de un login normal por `/ui/login`, un admin no tiene ninguna forma de llegar a su panel — este ticket agrega el punto de entrada en "Mi cuenta" y una pantalla de inicio del panel filtrada por rol.

**Depende de:** ticket 024 (fragmento de íconos, agente ux-ui-designer ya reforzado).

## Criterios de aceptación (TDD)
- Dado JWT con `role=TENANT_ADMIN` o `role=PLATFORM_ADMIN`, cuando cargo `/ui/cuenta`, entonces veo un botón "Ir al panel de administración".
- Dado JWT con `role=NONE` o sin rol, cuando cargo `/ui/cuenta`, entonces no veo ese botón.
- Dado que soy admin del tenant A pero la sesión activa es de otra app (otro `client_id`), cuando cargo `/ui/cuenta` de esa sesión, entonces tampoco veo el botón — el rol se lee siempre del JWT de la sesión actual (`AuthCoreUi.currentRole()`, ya existente desde el ticket 020), nunca se asume entre apps. Prueba explícita de este caso, no solo el caso positivo.
- Nueva ruta `/ui/admin` (pantalla de inicio del panel) + template nuevo, reutilizando el fragmento de shell existente.
- Dado `platform_admin`, cuando entro a `/ui/admin`, entonces veo 3 tarjetas: Clientes, Métricas, Proveedores de login.
- Dado `tenant_admin`, cuando entro a `/ui/admin`, entonces veo solo 2 tarjetas: Métricas, Proveedores de login.
- Se agrega como primer ítem del sidenav ("Inicio").
- Sin cambios de backend — se apoya enteramente en el JWT ya emitido.

## Hecho
- HU-1: botón "Ir al panel de administración" en `cuenta.html`, oculto por defecto, revelado por JS solo si `role` de la sesión actual es `TENANT_ADMIN`/`PLATFORM_ADMIN`. Caso "otra app" cubierto — el rol siempre viene del JWT de la sesión activa.
- HU-2: `GET /ui/admin` + `admin-home.html`, tarjetas filtradas por rol (3 para platform_admin, 2 para tenant_admin), link "Inicio" agregado al sidenav.
- Caso `role=NONE` explícito: mensaje de "sin acceso" con link a login, sin tarjetas.
- Bug real pre-existente encontrado y corregido en vivo: `.hidden` perdía contra la especificidad de `.admin-sidenav a`/`section.card` (ambos `display:flex`) desde el ticket 020 — "Clientes" nunca se ocultaba de verdad vía CSS para un tenant_admin. Fix con `!important` en `admin.css`/`app.css`, verificado en vivo.
- Ejecutado con delegación real al rol frontend-dev (workaround general-purpose + persona inyectada).
- Verificado en vivo con los 3 roles reales (platform_admin, tenant_admin, sin rol).
- 260/260 tests en verde (1 nuevo). Detalle completo en `docs/ARQUITECTURA.md` (sección "Ticket 025").
