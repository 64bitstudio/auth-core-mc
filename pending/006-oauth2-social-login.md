# 006 — Login social: Google, Facebook, Apple

## Objetivo
Integración OAuth2/OIDC con Google, Facebook y Apple Sign In. Habilitación y credenciales (`client_id`/`client_secret` cifrado) configurables por tenant vía API (ver `API.md`), con la UI de administración como cliente de esa API.

## Criterios de aceptación (TDD)
- Un tenant sin un proveedor habilitado no debe mostrar ese botón en la UI ni aceptar ese flujo en la API.
- `client_secret` nunca se expone en claro por ningún endpoint de lectura.
- Apple Sign In queda bloqueado hasta que el Product Owner confirme la membresía de Apple Developer Program ($99/año).
