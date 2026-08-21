# 001 — Modelo de dominio y migraciones

## Objetivo
Definir y migrar (Flyway) las tablas base: `tenant`, `user` (email/teléfono opcionalmente nulos pero al menos uno obligatorio, password_hash Argon2id, nombre, apellidos, flags de verificación), `tenant_identity_provider` (config de Google/Facebook/Apple por tenant, `client_secret` cifrado a nivel de aplicación), `oauth2_client` (apps registradas), `refresh_token`.

## Criterios de aceptación (TDD)
- Tests de repositorio para cada entidad antes de escribir el código de persistencia.
- Constraint a nivel de BD: `email IS NOT NULL OR phone IS NOT NULL`.
- Migración reproducible desde cero vía `docker compose up` + Flyway.

## Notas de arquitectura
Ver `docs/ARQUITECTURA.md` y `docs/BASE_DE_DATOS.md`.

## Hecho (TDD real: rojo → verde)
- Proyecto Spring Boot 4.1 + Gradle generado (`backend/`), JDK 25 (Java 21 no estaba instalado; 25 es la LTS disponible).
- 5 entidades JPA: `Tenant`, `User` (tabla `app_user` — `user` es palabra reservada en Postgres), `TenantIdentityProvider`, `IdentityClient` (tabla `identity_client`, no `oauth2_client`, para no chocar con el esquema propio de Spring Authorization Server que decidirá el ticket 007), `RefreshToken`.
- Migración `V1__init.sql`: incluye el CHECK `email IS NOT NULL OR phone IS NOT NULL` y los UNIQUE por tenant (email, phone).
- 20 tests (5 unitarios de dominio + 15 de repositorio con Testcontainers/Postgres real). Todos en verde.
- **Corrección de proceso importante**: `@DataJpaTest` genera esquema desde las anotaciones JPA por defecto, ignorando Flyway — eso habría dejado pasar tests contra un esquema paralelo que nunca se valida contra la migración real. Se forzó `spring.jpa.hibernate.ddl-auto=validate` en `src/test/resources/application.properties` para que los tests dependan exclusivamente de `V1__init.sql`.
- **Corrección de rigor en tests**: dos aserciones inicialmente aceptaban "cualquier `RuntimeException`" (pasarían igual sin el constraint real). Se corrigieron para exigir el tipo de excepción exacto y el nombre del constraint en el mensaje — al hacerlo, se confirmó que las excepciones lanzadas directamente vía `EntityManager.flush()` (fuera del proxy `@Repository`) llegan como `org.hibernate.exception.ConstraintViolationException`, no como `DataIntegrityViolationException` de Spring.
- Dependencia externa detectada: OrbStack (Docker) se suspende por inactividad y detiene los contenedores de Testcontainers/SonarQube; hubo que reactivarlo varias veces durante este ticket. Ver `docs/README.md`.
