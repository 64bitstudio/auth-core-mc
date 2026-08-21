# 006 — Login social: Google, Facebook, Apple

## Objetivo
Integración OAuth2/OIDC con Google, Facebook y Apple Sign In. Habilitación y credenciales (`client_id`/`client_secret` cifrado) configurables por tenant vía API (ver `API.md`), con la UI de administración como cliente de esa API.

## Criterios de aceptación (TDD)
- Un tenant sin un proveedor habilitado no debe mostrar ese botón en la UI ni aceptar ese flujo en la API.
- `client_secret` nunca se expone en claro por ningún endpoint de lectura.
- Apple Sign In queda bloqueado hasta que el Product Owner confirme la membresía de Apple Developer Program ($99/año).

## Hecho (TDD real: rojo → verde)
- `TenantIdentityProviderService` — configurar/deshabilitar Google y Facebook, `client_secret` cifrado con `SecretEncryptor` (ticket 005), nunca expuesto en claro.
- Apple rechazado explícitamente (`UnsupportedProviderException`), no silenciosamente ignorado.
- Endpoints `GET/PUT/DELETE /api/v1/identity-providers/*` — **el primer endpoint del proyecto que requiere autenticación** (no está en `permitAll`), por ser una acción de administración real. Probado con `@WithMockUser` (lógica) y sin él (confirma 401 fail-closed).
- **Credenciales reales obtenidas**: proyecto GCP `auth-core-mc` y app de Meta `Auth Core MC` creados junto al Product Owner vía navegador (Claude in Chrome), con su confirmación explícita para aceptar los términos de cada plataforma. `client_id`/`client_secret` en `backend/.env` (gitignored).
- **Decisión de secuenciación descubierta y documentada** (no un descuido): el flujo real de redirect+callback de OAuth2 social queda pospuesto — depende de tokens reales (ticket 007) o de sesión de UI (ticket 009), ninguno existe todavía. Ver `docs/ARQUITECTURA.md`.
- 20 tests nuevos — 148/148 en verde en el proyecto completo.
