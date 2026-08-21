# Base de datos — auth-core-mc

> Esquema de tablas, relaciones y para qué sirve cada campo. Se actualiza cuando se completa el ticket `001-modelo-dominio-y-migraciones` y cualquier ticket posterior que cambie el esquema.

## Estado actual
**Implementado y verificado** (ticket `001`, en `/done`). Migración real: `backend/src/main/resources/db/migration/V1__init.sql`. Los tests de repositorio (`backend/src/test/.../repository/`) corren contra esta migración exacta — `spring.jpa.hibernate.ddl-auto=validate` en los tests evita que Hibernate genere un esquema paralelo desde las entidades, así que lo que ves aquí es literalmente lo que existe en la base de datos.

**Nota de nombres:** la tabla de usuarios se llama `app_user`, no `user` — `USER` es palabra reservada en PostgreSQL/ANSI SQL. La tabla de clientes OAuth2 se llama `identity_client`, no `oauth2_client` — para no chocar con el esquema propio que trae Spring Authorization Server (`oauth2_registered_client`, etc.), que el ticket `007` decidirá cómo reconciliar.

## Diagrama de entidades

```
tenant ──┬──< app_user
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
| `totp_secret_encrypted` | text, nullable | Secreto TOTP cifrado, si activó 2FA por app autenticadora |
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

## `identity_client`
Una aplicación registrada que puede pedir tokens a este servicio (ver nota de nombres arriba: no se llama `oauth2_client` a propósito).

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
