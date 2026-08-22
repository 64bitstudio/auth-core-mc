# Base de datos — auth-core-mc

> Esquema de tablas, relaciones y para qué sirve cada campo. Se actualiza cuando se completa el ticket `001-modelo-dominio-y-migraciones` y cualquier ticket posterior que cambie el esquema.

## Estado actual
**Implementado y verificado** (tickets `001` y `005`, en `/done`). Migraciones reales: `V1__init.sql` + `V2__two_factor_method.sql` en `backend/src/main/resources/db/migration/`. Los tests de repositorio (`backend/src/test/.../repository/`) corren contra estas migraciones exactas — `spring.jpa.hibernate.ddl-auto=validate` en los tests evita que Hibernate genere un esquema paralelo desde las entidades, así que lo que ves aquí es literalmente lo que existe en la base de datos.

**Nota de nombres:** la tabla de usuarios se llama `app_user`, no `user` — `USER` es palabra reservada en PostgreSQL/ANSI SQL. La tabla de clientes OAuth2 se llama `identity_client`, no `oauth2_client` — para no chocar con el esquema propio que trae Spring Authorization Server (`oauth2_registered_client`, etc.), que el ticket `007` decidirá cómo reconciliar.

## Diagrama de entidades

```
tenant ──┬──< app_user ──< external_identity >── tenant (tenant_id denormalizado)
         ├──< tenant_identity_provider
         └──< identity_client ──< refresh_token >── app_user
```

## `tenant`
Representa un proyecto/cliente que usa este servicio de identidad.

| Campo | Tipo | Para qué sirve |
|---|---|---|
| `id` | UUID | Identificador único del tenant |
| `name` | text | Nombre interno del proyecto/cliente |
| `app_name` | text | Nombre mostrado en la UI de login (parametrizable) |
| `primary_color` | text | Color de marca para la UI (parametrizable) |
| `access_token_ttl_seconds` | int | Cuánto dura un access token antes de expirar (parametrizable) |
| `refresh_token_ttl_seconds` | int | Cuánto dura un refresh token (parametrizable) |
| `email_verification_ttl_seconds` | int | Vigencia del link de verificación de cuenta |
| `password_reset_ttl_seconds` | int | Vigencia del token de recuperación de contraseña |
| `otp_ttl_seconds` | int | Vigencia de un código OTP (SMS/correo) |
| `created_at` | timestamp | Auditoría |

## `app_user`
Un usuario final, siempre asociado a un `tenant`.

| Campo | Tipo | Para qué sirve |
|---|---|---|
| `id` | UUID | Identificador único |
| `tenant_id` | UUID (FK) | A qué proyecto/cliente pertenece |
| `email` | text, nullable | Correo (obligatorio si no hay `phone`) |
| `phone` | text, nullable | Teléfono (obligatorio si no hay `email`) |
| `nombre` | text | Nombre de pila |
| `apellidos` | text | Apellidos |
| `password_hash` | text, nullable | Hash Argon2id (nulo si el usuario solo usa login social) |
| `email_verified` | boolean | Si confirmó su correo |
| `phone_verified` | boolean | Si confirmó su teléfono |
| `totp_secret_encrypted` | text, nullable | Secreto TOTP cifrado (AES-256-GCM vía `SecretEncryptor`, no hash — necesita leerse en claro para calcular códigos), si activó 2FA por app autenticadora |
| `two_factor_method` | enum (`NONE`,`OTP_EMAIL`,`OTP_SMS`,`TOTP`) | Qué segundo factor eligió el usuario, si alguno (ticket `005`, migración `V2`) |
| `created_at` | timestamp | Auditoría |

_Constraint `app_user_email_or_phone_required`: `email IS NOT NULL OR phone IS NOT NULL`. También hay UNIQUE por tenant en `email` y en `phone` (`app_user_tenant_email_unique`, `app_user_tenant_phone_unique`) — Postgres trata cada `NULL` como distinto, así que cualquier cantidad de usuarios "solo teléfono" o "solo correo" puede coexistir sin chocar entre sí._

## `tenant_identity_provider`
Configuración de login social, por tenant.

| Campo | Tipo | Para qué sirve |
|---|---|---|
| `id` | UUID | Identificador único |
| `tenant_id` | UUID (FK) | A qué proyecto pertenece esta configuración |
| `provider` | enum (`google`,`facebook`,`apple`) | Qué proveedor social es |
| `enabled` | boolean | Si está activo para este tenant |
| `client_id` | text | Client ID entregado por el proveedor (Google/Facebook/Apple) |
| `client_secret_encrypted` | text | Client secret, cifrado a nivel de aplicación (ver `ARQUITECTURA.md` sección 6) |

## `external_identity`
Vincula un `app_user` con su identidad en un proveedor externo (Google/Facebook) — ticket `035`, primer ticket de la épica de login social real (`docs/definiciones/login-social-real.md`). Tabla hermana de `tenant_identity_provider`, mismo patrón de `tenant_id` denormalizado.

| Campo | Tipo | Para qué sirve |
|---|---|---|
| `id` | UUID | Identificador único |
| `tenant_id` | UUID (FK) | Denormalizado — mismo patrón que `login_event`/`tenant_identity_provider` |
| `user_id` | UUID (FK), NOT NULL | Cuenta local a la que queda vinculada esta identidad |
| `provider` | enum (`GOOGLE`,`FACEBOOK`,`APPLE`) | Reutiliza `IdentityProviderType`, el mismo tipo que `tenant_identity_provider` |
| `provider_user_id` | text, NOT NULL | El `sub` (Google) / `id` (Facebook) del proveedor — nunca el email, que puede cambiar del lado del proveedor |
| `linked_at` | timestamp | Cuándo se vinculó |

_Dos constraints UNIQUE: `external_identity_tenant_provider_unique` (`tenant_id`, `provider`, `provider_user_id`) — la misma cuenta social no puede vincularse dos veces dentro del mismo tenant; y `external_identity_user_provider_unique` (`user_id`, `provider`) — un `app_user` no puede tener más de un vínculo con el mismo proveedor, pero sí con proveedores distintos._

## `identity_client`
Una aplicación registrada que puede pedir tokens a este servicio (ver nota de nombres arriba: no se llama `oauth2_client` a propósito).

**Ticket `007`**: no agregó columnas — `TenantAwareRegisteredClientRepository` adapta cada fila de esta tabla a un `RegisteredClient` de Spring Authorization Server en el momento de la consulta (no hay tabla espejo `oauth2_registered_client`). El scope (`openid profile`) y los grants (`authorization_code`, `refresh_token`) salen hardcodeados en el adaptador, no de columnas nuevas — parametrizarlos por cliente es una extensión futura, no necesaria para este ticket.

| Campo | Tipo | Para qué sirve |
|---|---|---|
| `id` | UUID | Identificador único |
| `tenant_id` | UUID (FK) | A qué proyecto pertenece este cliente |
| `client_id` | text | Identificador público del cliente OAuth2 |
| `client_secret_hash` | text, nullable | Solo para clientes confidenciales |
| `is_first_party` | boolean | Si puede usar el grant de login directo (ver ticket `007`) |
| `redirect_uris` | text[] | URIs permitidas para el flujo Authorization Code |

## `refresh_token`
| Campo | Tipo | Para qué sirve |
|---|---|---|
| `id` | UUID | Identificador único |
| `user_id` | UUID (FK) | Usuario dueño del token |
| `client_id` | UUID (FK) | Cliente OAuth2 que lo emitió |
| `token_hash` | text | Nunca se guarda el token en claro |
| `revoked` | boolean | Revocación (también reflejada en Redis para efecto inmediato) |
| `expires_at` | timestamp | Expiración |
