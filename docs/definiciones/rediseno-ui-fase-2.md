# Definición: Rediseño UI — fase 2 (navegación admin, iconografía y vistas del panel)

## Resumen ejecutivo
Segunda fase del rediseño de UI (la primera, tickets 020-023, dejó un sistema visual propio para el panel y estados de carga en las páginas de usuario final). Esta fase resuelve tres cosas encontradas al usar la interfaz ya en producción: (1) no hay ninguna forma de llegar al panel de administración después de iniciar sesión normal, (2) el panel se siente "rudimentario" — sin íconos, contenido no centrado, botones inconsistentes — y (3) la pantalla de métricas muestra todo en texto plano en vez de gráficas. Incluye también mejorar las instrucciones del subagente `ux-ui-designer` para que estas carencias no se repitan en futuras entregas.

## Objetivo de negocio
Un administrador (tenant_admin o platform_admin) que inicia sesión por la UI pública no tiene ninguna forma de llegar a su panel salvo escribiendo la URL de memoria — un flujo real, no cosmético, que hoy simplemente no existe. Además, el panel ya construido (tickets 011-022) funciona correctamente pero se ve y se siente por debajo del estándar visual que Marco espera de un producto propio: sin apoyos visuales, con inconsistencias de layout que un usuario real notaría de inmediato.

**Usuarios/roles involucrados:** `tenant_admin` (administra su propio tenant), `platform_admin` (administra todos los tenants), usuarios sin rol (`NONE`, consumidores normales — no deben ver nada de esto).

## Alcance

### Incluye
- Un punto de entrada real al panel desde `/ui/cuenta`, visible solo cuando corresponde según el rol de la sesión actual.
- Una pantalla de inicio del panel que solo muestra las secciones a las que el rol actual tiene acceso.
- Corrección de layout: contenido centrado, convención única de tamaño de botones (fin a los desbordes e inconsistencias).
- Sistema de iconografía SVG inline: sidenav, estados vacíos, logos de marca de los proveedores de login.
- Alta de tenant vía botón "+ Añadir cliente" + modal (reemplaza el formulario embebido al fondo de la tabla).
- Métricas mostradas gráficamente (tarjetas de estadística + gráficas SVG de barras/dona sobre los datos agregados que el backend ya expone).
- Rediseño de la pantalla de proveedores de login social (Google/Facebook): logos de marca, consistencia de botones.
- Animaciones/transiciones sutiles (apertura de modales, hover/focus), respetando `prefers-reduced-motion`.
- Mejora de las instrucciones del subagente `ux-ui-designer` (`~/.claude/agents/ux-ui-designer.md`) — aplicada primero, para que el resto de este documento ya nazca cumpliéndola.

### No incluye
- Gráfica de tendencia en el tiempo (serie temporal día a día) en métricas — el endpoint actual (ticket 016) solo agrega totales del rango solicitado, no los agrupa por día. Agregar eso es un cambio de backend con su propio alcance; queda para un ticket futuro si se decide.
- Selector de tenant en los proveedores de identidad (ya excluido desde el documento original).
- Dark mode (ya excluido desde el documento original).
- Cambiar el stack: sigue siendo Thymeleaf + JS vanilla + CSS — los íconos y gráficas son SVG inline hechos a mano, cero librerías/dependencias nuevas (decisión confirmada con el Product Owner, ver Diseño técnico).
- Volver a tocar las 7 páginas de usuario final (`app.css`) — eso se cerró en el ticket 023, sin cambios pendientes ahí.
- Reabrir la decisión del ticket 019 (que `tenant_admin` no vea "Clientes") — sigue igual; el selector del panel de inicio simplemente no le ofrece esa tarjeta.

## Historias de Usuario

### HU-1: Entrada al panel desde "Mi cuenta"
Como tenant_admin o platform_admin, quiero ver un acceso claro a mi panel de administración desde "Mi cuenta", para no tener que escribir la URL de memoria.

Criterios de aceptación:
- Dado que inicio sesión y el JWT de esa sesión trae `role=TENANT_ADMIN` o `role=PLATFORM_ADMIN`, cuando cargo `/ui/cuenta`, entonces veo un botón "Ir al panel de administración".
- Dado que mi JWT trae `role=NONE` o no trae rol, cuando cargo `/ui/cuenta`, entonces no veo ese botón.
- Dado que soy admin del tenant A pero la sesión activa es de otra app/tenant (otro `client_id`), cuando cargo `/ui/cuenta` de esa sesión, entonces tampoco veo el botón — el rol siempre se lee del JWT de la sesión actual, nunca se asume entre apps.
- El botón lleva a la pantalla de inicio del panel (HU-2), no directo a una sección específica.

### HU-2: Página de inicio del panel (selector por rol)
Como admin, quiero llegar a una pantalla de inicio del panel que solo me muestre las secciones a las que tengo acceso, para no toparme con enlaces que solo van a darme 403.

Criterios de aceptación:
- Dado que soy `platform_admin`, cuando entro al panel, entonces veo 3 tarjetas: Clientes, Métricas, Proveedores de login.
- Dado que soy `tenant_admin`, cuando entro al panel, entonces veo solo 2 tarjetas: Métricas, Proveedores de login (Clientes no aparece, mismo criterio ya usado en el sidenav desde el ticket 020).
- Esta pantalla se agrega también como primer ítem del sidenav ("Inicio").

### HU-3: Layout centrado y botones sin desborde
Como cualquier usuario del panel, quiero que el contenido esté centrado y ningún botón se corte, para que la interfaz se vea profesional en cualquier tamaño de pantalla.

Criterios de aceptación:
- Dado un viewport ancho (1400px+), cuando veo **cualquiera** de las páginas del panel (Clientes, Métricas, Proveedores de login, y la nueva pantalla de inicio de HU-2), entonces el área de contenido queda centrada en el espacio disponible junto al sidenav — no pegada a su borde izquierdo como hoy (`.admin-content` tiene `max-width` pero le falta el `margin: 0 auto` que lo centre; defecto confirmado en vivo en las 3 páginas existentes, no solo en una).
- Dado específicamente `/ui/admin/tenants` ("Clientes"), cuando veo la tabla y sus botones de Editar/Desactivar/Reactivar, entonces ninguno se corta ni se desborda de su celda — este es el caso concreto que originó el hallazgo, se verifica explícitamente en vivo antes de cerrar el ticket.
- Dado cualquier botón o grupo de botones del panel, cuando la página carga, entonces ningún texto se corta ni se sale de su contenedor, verificado en vivo en al menos 2 anchos de viewport distintos.
- Se define una convención única: un botón es la única acción de un formulario → ocupa el ancho del contenedor; un grupo de botones (ej. Guardar + Desactivar en proveedores) → todos del mismo ancho entre sí, nunca un full-width mezclado con uno auto-width en el mismo grupo (defecto confirmado en vivo en "Proveedores de login").

### HU-4: Sistema de iconografía e ilustraciones
Como usuario del panel, quiero apoyos visuales (íconos, ilustraciones) en vez de solo texto, para identificar cada sección/acción/estado más rápido.

Criterios de aceptación:
- Dado el sidenav, cuando lo veo, entonces cada sección (Inicio, Clientes, Métricas, Proveedores de login) tiene un ícono SVG inline junto a su texto.
- Dado el encabezado (`h2`) de cualquier página del panel, cuando la cargo, entonces tiene un ícono de apoyo junto al título — no solo texto, en ninguna de las páginas.
- Dado un estado vacío (ej. "No hay clientes registrados todavía"), cuando lo veo, entonces incluye una ilustración/ícono simple, no solo texto.
- Dado un proveedor de login social, cuando veo su tarjeta, entonces muestra el logo de marca correspondiente (Google, Facebook) en vez de solo el nombre en texto.
- Dado las tarjetas de estadística de Métricas (HU-6) y las secciones de contenido en general, cuando las veo, entonces cada una tiene un ícono propio que refuerza qué representa (ej. reloj para latencia, personas para usuarios activos) — la iconografía cubre el panel completo, no un único punto aislado.
- Todos los íconos son SVG inline (nunca fuentes de íconos ni imágenes externas — coherente con la política de cero dependencias del proyecto), con `aria-hidden="true"` cuando son decorativos y texto accesible cuando transmiten información por sí solos.

### HU-5: Alta de tenant vía modal
Como platform_admin, quiero dar de alta un cliente nuevo desde un botón "+ Añadir cliente" que abra un modal, en vez de un formulario largo al fondo de la página, para no hacer scroll solo para ver la lista.

Criterios de aceptación:
- Dado que estoy en "Clientes", cuando veo la tabla, entonces hay un botón "+ Añadir cliente" cerca del encabezado (no al fondo de la página).
- Dado que hago clic en ese botón, cuando se abre, entonces aparece un `<dialog>` de creación (mismo patrón ya usado para editar desde el ticket 021), no un formulario embebido en el flujo de la página.
- Dado que cancelo el modal, cuando lo cierro, entonces no se crea ningún tenant.
- El formulario embebido actual al fondo de la página se elimina por completo.

### HU-6: Métricas gráficas
Como admin, quiero ver las métricas de uso de forma gráfica en vez de texto plano, para interpretarlas de un vistazo.

Criterios de aceptación:
- Dado que cargo "Métricas", cuando los datos llegan, entonces veo tarjetas de estadística (con ícono) para logins totales, usuarios activos, usuarios registrados y latencia promedio.
- Dado que hay logins exitosos y fallidos en el rango, cuando veo los resultados, entonces hay una gráfica SVG (barra o dona) comparando éxitos vs. fallos — no el texto plano actual ("Éxitos: X — Fallos: Y").
- Dado que hay actividad en más de un proveedor (`byProvider`), cuando veo los resultados, entonces hay una gráfica de barras SVG por proveedor.
- El selector de tenant deja de ser un campo de texto libre con UUID crudo — ver decisión técnica abajo.
- El selector de rango de fechas incluye accesos rápidos (7/30/90 días) además de los date pickers ya existentes.

### HU-7: Rediseño de proveedores de login social
Como admin, quiero una pantalla de proveedores de login consistente y con identidad de marca, para configurar Google/Facebook con confianza.

Criterios de aceptación:
- Dado que veo una tarjeta de proveedor, cuando la cargo, entonces muestra el logo de marca correspondiente junto al nombre.
- Dado el grupo de botones de una tarjeta (Guardar / Desactivar), cuando los veo, entonces siguen la convención de HU-3 (mismo ancho entre sí).
- El formulario puede reorganizarse (ej. layout de 2 columnas ya usado en el modal de edición de tenant) si mejora la jerarquía visual.

### HU-8: Animaciones y transiciones sutiles
Como usuario del panel, quiero transiciones suaves al abrir modales o interactuar con botones, para que la interfaz se sienta pulida.

Criterios de aceptación:
- Dado que abro cualquier `<dialog>`, cuando aparece, entonces tiene una transición de entrada breve (fade/scale), no un aparecer instantáneo.
- Dado que interactúo con un botón/link, cuando hago hover/focus, entonces hay una transición suave y consistente.
- Dado que el usuario tiene `prefers-reduced-motion: reduce` activado, cuando interactúa con cualquier elemento animado, entonces las animaciones se desactivan o se reducen a un cambio instantáneo (mismo criterio ya aplicado a los spinners del ticket 023).

### HU-9: Mejora de las instrucciones del subagente `ux-ui-designer`
Como Product Owner, quiero que el subagente `ux-ui-designer` exija por defecto iconografía/ilustraciones, animaciones con `prefers-reduced-motion` y SVGs inline, para que futuras entregas de UI no repitan estas carencias.

Criterios de aceptación:
- `~/.claude/agents/ux-ui-designer.md` incluye estos requisitos explícitamente en "Tu enfoque", no como sugerencia opcional.
- Se aplica antes de instanciar el agente para el resto de los HUs de este documento.
- El agente participa realmente en el flujo (mockup/decisiones de diseño documentadas antes de implementar), no solo se actualiza su archivo sin usarse.

## Diseño técnico

- **Cero dependencias nuevas, confirmado con el Product Owner**: los íconos son SVG inline (fragmento Thymeleaf compartido, mismo patrón que `fragments/admin-shell.html`) y las gráficas de métricas son SVG generado a mano por un nuevo `static/js/charts.js` (funciones puras: dato → string SVG), sin ninguna librería de gráficas. Coherente con la política de cero dependencias externas que el proyecto mantiene desde el ticket 009.
- **Selector de tenant en métricas**: `platform_admin` ya puede llamar `GET /api/v1/admin/tenants` (lista completa) — se puebla un `<select>` real con nombre + id, sin cambios de backend. `tenant_admin` no puede llamar esa lista (403 desde el ticket 019) y solo puede consultar su propio tenant — para ese rol el selector se oculta y se muestra el nombre de su tenant como texto fijo (ya disponible: su `tenant_id` viene en el JWT). Ninguna llamada nueva al backend.
- **Botón "Ir al panel" y pantalla de inicio**: se apoyan en `AuthCoreUi.currentRole()` (ya existe desde el ticket 020) — sin cambios de backend. La nueva pantalla de inicio (`/ui/admin`) es un template nuevo + una ruta nueva en `UiPagesController`, reutilizando el fragmento de shell existente.
- **Sin gráfica de tendencia en el tiempo**: el backend de métricas (`AdminMetricsService`) solo agrega totales del rango — no agrupa por día. Confirmado leyendo el código antes de prometer algo que no se puede construir sin tocar el backend (ver "No incluye").
- **Convención de botones**: se documenta como regla de `admin.css` (un `.button-group` con ancho uniforme entre sus hijos) en vez de dejarlo a criterio de cada página — evita que el mismo problema reaparezca en una futura pantalla.

## Diagramas

```mermaid
flowchart TD
    A[POST /api/v1/login] --> B[/ui/cuenta]
    B --> C{JWT role de esta sesión}
    C -->|NONE| D[Sin botón de panel]
    C -->|TENANT_ADMIN o PLATFORM_ADMIN| E["Botón: Ir al panel de administración"]
    E --> F[/ui/admin — Inicio del panel]
    F --> G{role}
    G -->|PLATFORM_ADMIN| H["Tarjetas: Clientes, Métricas, Proveedores"]
    G -->|TENANT_ADMIN| I["Tarjetas: Métricas, Proveedores"]
```
Muestra cómo el rol de la sesión actual (no un estado global) decide si aparece el botón en "Mi cuenta" y qué tarjetas ve en la nueva pantalla de inicio del panel — resuelve directamente la ambigüedad planteada sobre "otra app" filtrando siempre por el JWT de esa sesión específica.

## Riesgos y preguntas abiertas
Ninguna — las 4 decisiones que tenían más de una solución válida (dónde vive la entrada al panel, a dónde aterriza un tenant_admin, cómo se resuelven las gráficas sin dependencias nuevas, y si el subagente ux-ui-designer participa de verdad) ya se resolvieron explícitamente con el Product Owner antes de escribir este documento.

## Impacto estimado
Tickets tentativos (se confirman/ajustan al usar el skill `nuevo-ticket` después del VoBo):

- **024** — Mejora de instrucciones del subagente `ux-ui-designer` (HU-9) + sistema de íconos SVG reutilizable (fragmento compartido, base técnica de HU-4). Va primero porque el resto de los tickets depende de ambos.
- **025** — Navegación admin↔consumidor: botón en "Mi cuenta" + pantalla de inicio del panel (HU-1, HU-2).
- **026** — Layout centrado + convención de botones sin desborde (HU-3), aplicado a las 3 páginas existentes + la nueva pantalla de inicio.
- **027** — Alta de tenant vía modal (HU-5).
- **028** — Métricas gráficas (HU-6).
- **029** — Rediseño de proveedores de login social (HU-7).
- **030** — Animaciones/transiciones + revisión final de accesibilidad con `prefers-reduced-motion` (HU-8) — al final, para no repetir trabajo si algo de diseño cambia en los tickets anteriores.
