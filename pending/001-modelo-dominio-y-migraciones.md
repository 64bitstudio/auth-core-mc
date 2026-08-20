# 001 — Modelo de dominio y migraciones

## Objetivo
Definir y migrar (Flyway) las tablas base: `tenant`, `user` (email/teléfono opcionalmente nulos pero al menos uno obligatorio, password_hash Argon2id, nombre, apellidos, flags de verificación), `tenant_identity_provider` (config de Google/Facebook/Apple por tenant, `client_secret` cifrado a nivel de aplicación), `oauth2_client` (apps registradas), `refresh_token`.

## Criterios de aceptación (TDD)
- Tests de repositorio para cada entidad antes de escribir el código de persistencia.
- Constraint a nivel de BD: `email IS NOT NULL OR phone IS NOT NULL`.
- Migración reproducible desde cero vía `docker compose up` + Flyway.

## Notas de arquitectura
Ver `docs/ARQUITECTURA.md` y `docs/BASE_DE_DATOS.md`.
