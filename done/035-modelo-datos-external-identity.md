# 035 — Modelo de datos: tabla `external_identity`

## Objetivo
Nace de la fase de definición `docs/definiciones/login-social-real.md` (Diseño técnico, decisión 6). Primer ticket de la épica de login social real — crea la tabla que vincula un `app_user` con su identidad externa (Google/Facebook), permitiendo múltiples proveedores por usuario.

**Depende de:** ninguno de los tickets de esta épica (es el primero). Es prerequisito de 037-042.

## Criterios de aceptación (TDD)
- Migración Flyway nueva `V8__external_identity.sql` con la tabla `external_identity`: `id` (PK), `tenant_id` (FK a `tenant`, denormalizado explícito — mismo patrón que `login_event`/`tenant_identity_provider`), `user_id` (FK a `app_user`, NOT NULL), `provider` (reutiliza el enum `IdentityProviderType` ya existente), `provider_user_id` (text NOT NULL — el `sub` de Google / `id` de Facebook, nunca el email), `linked_at` (timestamp).
- Constraints: `UNIQUE(tenant_id, provider, provider_user_id)` (la misma cuenta social no puede vincularse dos veces dentro del mismo tenant) y `UNIQUE(user_id, provider)` (un `app_user` no puede tener más de un vínculo con el mismo proveedor — pero SÍ puede tener vínculos con proveedores distintos, ver Decisión 6 de la definición).
- Entidad JPA `ExternalIdentity` + repositorio `ExternalIdentityRepository` con al menos: `findByTenantAndProviderAndProviderUserId(...)` (para resolver login social entrante) y `findByUser(...)` (para listar proveedores vinculados, útil en `/ui/cuenta`).
- Tests de repositorio confirmando ambas constraints de unicidad (intento de duplicado lanza excepción de integridad).
- Migración probada contra la BD local real, no solo Testcontainers — confirmar que corre limpia sobre el estado actual de la BD de desarrollo.

## Hecho
- `V8__external_identity.sql`: tabla `external_identity` con `id`, `tenant_id`/`user_id` FK, `provider` (CHECK GOOGLE/FACEBOOK/APPLE, mismo patrón que `tenant_identity_provider`), `provider_user_id`, `linked_at`. Ambas constraints de unicidad (`tenant_id, provider, provider_user_id` y `user_id, provider`).
- Entidad `ExternalIdentity` + repositorio `ExternalIdentityRepository` (`findByTenantAndProviderAndProviderUserId`, `findByUser`), reutiliza `IdentityProviderType` tal cual.
- `ExternalIdentityRepositoryTest`: 5 tests — alta/lectura, múltiples proveedores distintos por usuario, ambas constraints de unicidad (con `ConstraintViolationException` explícito), y que la misma cuenta social sí puede vincularse en tenants distintos.
- **Verificado contra la BD local real** (no solo Testcontainers): `flyway migrate` vía Docker contra el Postgres real de `compose.yaml`, confirmado con `\d external_identity` (PK, ambas UNIQUE, CHECK y FKs correctos) y `flyway_schema_history` con la fila de V8.
- `docs/BASE_DE_DATOS.md`/`docs/ARQUITECTURA.md` actualizados.
- 265/265 tests en verde (5 nuevos).
- **Hallazgo de mejora continua, no bloqueante:** `docs/BASE_DE_DATOS.md` ya tenía drift previo a este ticket (no documentaba `login_event`, `tenant_wrapped_data_key`, `break_glass_audit_event`) — no se corrigió aquí (fuera del alcance de este ticket), queda como candidato a ticket de "poner al día BASE_DE_DATOS.md" o un hook que lo mantenga sincronizado en cada migración.
- Detalle completo en `docs/ARQUITECTURA.md` (sección "Ticket 035").
