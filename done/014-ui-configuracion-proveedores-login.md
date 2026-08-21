# 014 — UI de configuración de proveedores de login por cliente

## Objetivo
Interfaz en el panel para que un administrador (de plataforma o del propio tenant) configure/active/desactive los proveedores de login social (Google, Facebook) de su cliente. Consume la API ya existente (`TenantIdentityProviderService`, ticket 006) — no requiere tocar ese backend. Nace de `docs/definiciones/panel-administracion-clientes.md` (HU-2).

**Depende de:** tickets 011 (RBAC) y 012 (auth del panel). **Recomendado secuenciar después o junto con el ticket 017** (cifrado por sobres) antes de usarse con secretos de clientes externos reales — no es un bloqueo técnico duro, es una recomendación de orden de lanzamiento.

## Criterios de aceptación (TDD)
- La UI permite ingresar client_id/client_secret de Google/Facebook y guardarlos — el secreto nunca se muestra en claro después de guardado (ya garantizado por el backend existente).
- Para cambiar un secreto ya guardado, se debe ingresar uno nuevo completo — la UI no precarga ni permite edición parcial del valor enmascarado.
- Apple no aparece como opción configurable (ya rechazado desde ticket 006).
- Acceso gateado por rol (tickets 011/012).

## Hecho
- `AdminIdentityProviderController` nuevo (`/api/v1/admin/identity-providers`, JWT admin, gateado por la regla de rol ya existente de `/api/v1/admin/**` — sin cambios a `SecurityConfig`) delega directo a `TenantIdentityProviderService` (ticket 006/017), sin modificarlo.
- Página nueva `/ui/admin/identity-providers`: tarjetas de Google y Facebook (Apple no aparece), formulario de client_id/client_secret que siempre exige un valor nuevo completo — nunca precarga el secreto (el DTO `IdentityProviderView` nunca lo serializa, así que físicamente no hay nada que precargar), botón de desactivar visible solo cuando el proveedor está activo.
- El secreto, una vez guardado, nunca se vuelve a mostrar (ya garantizado por el backend existente, ticket 006).
- Acceso gateado por rol de verdad (403 real probado end-to-end para un usuario sin rol admin), no solo ocultando la UI.
- **Alcance deliberado**: el endpoint opera siempre sobre el tenant propio del admin (vía claim `tenant_id` del JWT) — un `platform_admin` configurando el tenant de otra persona necesitaría un selector de tenant, fuera de alcance aquí. Detalle completo en `docs/ARQUITECTURA.md` (sección "Ticket 014").
- **Primera UI real y autenticada del panel** — reutiliza el login existente del ticket 009 (dogfooding, mismo patrón que el ticket 012) en vez de una página de login separada. Requirió empezar a guardar el access token real en `sessionStorage` tras el login (antes se descartaba) — cambio aditivo, no afecta páginas existentes.
- 227/227 tests en verde (223 antes de este ticket + 4 nuevos).
