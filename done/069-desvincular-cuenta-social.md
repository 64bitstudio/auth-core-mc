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
Implementado y mergeado en PR #128 (`feat/069-desvincular-cuenta-social`).

- `ExternalIdentityRepository`: `findByUserAndProvider` + `countByUser`.
- `ExternalIdentityLinkService.unlink(user, provider)`: 404 `provider_not_linked`
  si no está vinculado; 409 `cannot_unlink_last_login_method` si el usuario no
  tiene contraseña y este es su único proveedor (nunca deja al usuario sin
  forma de entrar); borra la fila en cualquier otro caso.
- `DELETE /api/v1/account/connected-providers/{provider}` en
  `AccountLinkProviderController` → `204`; reutiliza `parseSupportedProvider`
  ya existente de `linkProvider` para el 400 `unsupported_provider`.
- `GlobalExceptionHandler`: mapeo de las 2 excepciones nuevas.
- Tests: `ExternalIdentityLinkServiceTest` (4 casos unitarios, Mockito) +
  3 casos end-to-end nuevos en `AccountLinkProviderControllerTest` (éxito,
  404, 409). Suite completa verde.
- `docs/API.md` actualizado con la nueva fila.
- Hallazgos reales de Sonar en el primer CI (S1128 import sin uso, S6068
  `eq()` innecesario en `verify(..., never())`, 2× S5778 lambda con múltiples
  invocaciones que pueden lanzar) — los 4 resueltos antes de mergear.

Consumido por galgoth-studio#095 (rediseño de "Mi Perfil"): el menú "···" de
cada proveedor conectado llama a este endpoint vía `accountApi.unlinkProvider`.
