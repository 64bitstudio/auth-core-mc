# 069 — Desvincular cuenta social conectada

## Objetivo
Ticket retroactivo. Hallazgo real al alinear "Mi Perfil" (galgoth-studio#079) con su mockup original: la sección "Cuentas conectadas" trae un menú "···" por proveedor (Google/Facebook) que normalmente incluiría "Desvincular" — esa función no existe hoy en absoluto, ni endpoint en auth-core-mc ni botón en la UI. Marco confirmó (vía `AskUserQuestion`) construirla ahora en vez de dejarla fuera.

## Criterios de aceptación (TDD)
- `DELETE /api/v1/account/connected-providers/{provider}` desvincula un proveedor real ya vinculado (`204`).
- Intentar desvincular un proveedor nunca vinculado responde `404 provider_not_linked`.
- **Nunca deja al usuario sin forma de entrar**: si no tiene contraseña (`passwordHash == null`) y este es su único proveedor vinculado, responde `409 cannot_unlink_last_login_method` y no borra nada.
- Un proveedor no soportado (`apple`, uno inventado) responde `400 unsupported_provider` (mismo criterio que `link-provider`).
- Suite completa en verde.

## Hecho
