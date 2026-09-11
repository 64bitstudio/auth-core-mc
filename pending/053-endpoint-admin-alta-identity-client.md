# 053 — Endpoint admin para dar de alta `identity_client`

## Objetivo
Cada `identity_client` creado hasta hoy (`mail-core-mc` en el ticket 048, `galgoth-studio` en el ticket 052, y el original `ticket-005-e2e-client`) se sembró a mano vía SQL directo por SSH, en cada ambiente por separado — sin auditoría, sin validación de aplicación (unicidad de `client_id`, forma de `redirect_uris`, etc.), y sin el mismo nivel de trazabilidad que ya tiene el alta de `tenant` desde el ticket 013 (`POST /api/v1/admin/tenants`). Este ticket cierra ese hueco: un endpoint admin equivalente para `identity_client`, análogo al patrón ya establecido por `AdminTenantController`/`AdminTenantService`.

## Alcance
**Incluye:**
- `POST /api/v1/admin/tenants/{tenantId}/clients` (mismo espacio de URL y gate de rol que `AdminTenantController`, ver ticket 012/013) — crea un `identity_client` para un tenant existente.
- Validación de aplicación: `client_id` único, `redirect_uris` no vacío para clientes no machine-to-machine, generación+hash del `client_secret` cuando `is_first_party=false` (nunca se devuelve en claro más de una vez, mismo criterio que `TenantSecretEncryptor`/`SecretEncryptor` ya usan para otros secretos).
- Soporte para los dos casos ya existentes en el dominio (ticket 048): cliente humano (`authorization_code`, first o third-party) y machine-to-machine (`client_credentials`, `scopes` explícitos).
- `GET`/`DELETE` (listar y desactivar/revocar) del mismo recurso — sin estos, dar de alta sigue sin cerrar el ciclo completo de administración.
- Actualiza `docs/API.md`/`docs/BASE_DE_DATOS.md`.

**No incluye:**
- Tocar el endpoint de `tenant` ya existente (ticket 013), ni su modelo de permisos.
- Un panel visual/UI para esto — solo el endpoint (`docs/definiciones/panel-administracion-clientes.md` ya cubre la parte de UI de proveedores sociales por tenant; si este endpoint necesita pantalla propia, es un ticket de UI aparte).
- Migrar retroactivamente los clientes ya sembrados a mano (048, 052, el de prueba) — quedan como están, este ticket es hacia adelante.

## Criterios de aceptación (TDD)
- `POST /api/v1/admin/tenants/{tenantId}/clients` con un `client_id` ya existente responde `409`/error explícito, no crea una fila duplicada.
- Un cliente creado con `is_first_party=false` recibe un `client_secret` en claro **solo en la respuesta de creación**, nunca de vuelta en un `GET` posterior (mismo contrato que `TenantIdentityProviderService`).
- Un cliente `is_machine_client=true` creado por este endpoint funciona de punta a punta con `grant_type=client_credentials` contra `/oauth2/token` (mismo test de verificación real que ya usa el ticket 048, esta vez disparado por el endpoint en vez de SQL a mano).
- El gate de rol reutiliza exactamente el de `AdminTenantController` (`TENANT_ADMIN`/`PLATFORM_ADMIN`, verificado vía JWT) — sin un mecanismo de autorización nuevo.
- Tests de repositorio/integración con Testcontainers reales, no solo mocks — mismo estándar que el resto del proyecto.

## Hecho
