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
