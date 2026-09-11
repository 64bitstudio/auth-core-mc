# 053 — Endpoints admin para dar de alta `identity_client` y promover el primer `TENANT_ADMIN`

## Objetivo
Cada `identity_client` creado hasta hoy (`mail-core-mc` en el ticket 048, `galgoth-studio` en el ticket 052, y el original `ticket-005-e2e-client`) se sembró a mano vía SQL directo por SSH, en cada ambiente por separado — sin auditoría, sin validación de aplicación, y sin el mismo nivel de trazabilidad que ya tiene el alta de `tenant` desde el ticket 013 (`POST /api/v1/admin/tenants`).

**Ampliado (2026-09-11, a raíz de la propuesta `PROP-GS-AUTH-01` de galgoth-studio):** se encontró un segundo gap del mismo tipo, más grave — `User.grantRole(UserRole)` existe en el dominio desde el ticket 011, pero **ningún controller ni service lo llama**. No hay ninguna forma de convertir a un usuario en `TENANT_ADMIN` o `PLATFORM_ADMIN` salvo un `UPDATE app_user SET role=...` manual en la base. Esto es más limitante que el gap de `identity_client`: el modelo de auto-servicio por tenant que ya existe (`PUT /api/v1/identity-providers/*`, gateado por `TENANT_ADMIN`, tickets 006/029) es inútil para un tenant nuevo mientras no exista *alguien* con ese rol — hoy ese primer admin solo puede nacer de SQL a mano, igual que el propio tenant/cliente.

## Alcance
**Incluye:**
- `POST /api/v1/admin/tenants/{tenantId}/clients` (mismo espacio de URL y gate de rol que `AdminTenantController`, ver ticket 012/013) — crea un `identity_client` para un tenant existente.
- Validación de aplicación: `client_id` único, `redirect_uris` no vacío para clientes no machine-to-machine, generación+hash del `client_secret` cuando `is_first_party=false` (nunca se devuelve en claro más de una vez, mismo criterio que `TenantSecretEncryptor`/`SecretEncryptor` ya usan para otros secretos).
- Soporte para los dos casos ya existentes en el dominio (ticket 048): cliente humano (`authorization_code`, first o third-party) y machine-to-machine (`client_credentials`, `scopes` explícitos).
- `GET`/`DELETE` (listar y desactivar/revocar) del mismo recurso — sin estos, dar de alta sigue sin cerrar el ciclo completo de administración.
- **Nuevo:** `POST /api/v1/admin/tenants/{tenantId}/users/{userId}/role` (o endpoint equivalente) que llama a `User.grantRole(...)` — permite promover a `TENANT_ADMIN` a un `app_user` ya registrado de ese tenant. Gate de rol: `PLATFORM_ADMIN` siempre puede; un `TENANT_ADMIN` existente puede promover/degradar dentro de su propio tenant (nunca a `PLATFORM_ADMIN`).
- **El caso "primer admin de un tenant nuevo" sigue necesitando arranque manual una vez** (no hay huevo-o-gallina que resolver mágicamente): el flujo pasa a ser *registrar el usuario por la API pública* (`POST /api/v1/register`, como cualquier usuario) y *luego* que un `PLATFORM_ADMIN` ya existente lo promueva con el endpoint nuevo — en vez de insertar la fila entera a mano por SQL. Reduce el SQL manual a "ninguno" para todo lo que sigue después del primer `PLATFORM_ADMIN` de la plataforma (ese sí queda fuera de alcance, ver abajo).
- Actualiza `docs/API.md`/`docs/BASE_DE_DATOS.md`.

**No incluye:**
- Tocar el endpoint de `tenant` ya existente (ticket 013), ni su modelo de permisos.
- Un panel visual/UI para esto — solo los endpoints (`docs/definiciones/panel-administracion-clientes.md` ya cubre la parte de UI de proveedores sociales por tenant; si estos endpoints necesitan pantalla propia, es un ticket de UI aparte).
- Migrar retroactivamente los clientes/usuarios ya sembrados a mano (048, 052, el de prueba) — quedan como están, este ticket es hacia adelante.
- **El primer `PLATFORM_ADMIN` de toda la plataforma** (el "root" que puede a su vez crear tenants y promover `TENANT_ADMIN`s): ese sigue siendo, por diseño, un bootstrap único fuera de la API — sin código que lo evite, tiene que existir *alguien* que empiece la cadena de confianza. Documentar ese procedimiento (probablemente una migración/seed one-shot o un comando de arranque) si no existe ya.

## Criterios de aceptación (TDD)
- `POST /api/v1/admin/tenants/{tenantId}/clients` con un `client_id` ya existente responde `409`/error explícito, no crea una fila duplicada.
- Un cliente creado con `is_first_party=false` recibe un `client_secret` en claro **solo en la respuesta de creación**, nunca de vuelta en un `GET` posterior (mismo contrato que `TenantIdentityProviderService`).
- Un cliente `is_machine_client=true` creado por este endpoint funciona de punta a punta con `grant_type=client_credentials` contra `/oauth2/token` (mismo test de verificación real que ya usa el ticket 048, esta vez disparado por el endpoint en vez de SQL a mano).
- El gate de rol de ambos endpoints nuevos reutiliza exactamente el de `AdminTenantController` (`TENANT_ADMIN`/`PLATFORM_ADMIN`, verificado vía JWT) — sin un mecanismo de autorización nuevo.
- Un `TENANT_ADMIN` no puede promover a nadie a `PLATFORM_ADMIN` ni tocar usuarios de otro tenant (`403`).
- Verificado en vivo: registrar un usuario real vía `/api/v1/register`, promoverlo a `TENANT_ADMIN` vía el endpoint nuevo, y confirmar que puede llamar `PUT /api/v1/identity-providers/GOOGLE` de su propio tenant sin ningún `UPDATE` SQL de por medio.
- Tests de repositorio/integración con Testcontainers reales, no solo mocks — mismo estándar que el resto del proyecto.

## Hecho
