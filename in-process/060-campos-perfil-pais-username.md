# 060 — Campos de perfil: país/región y nombre de usuario

## Objetivo
Ver `docs/definiciones/perfil-de-usuario.md` de galgoth-studio (VoBo de
Marco recibido) — HU-1. `User` hoy solo tiene nombre/apellidos/email/
phone — la pantalla "Mi Perfil" de galgoth-studio necesita país/región y
nombre de usuario, que son datos de identidad de la persona (viven aquí,
no en galgoth-studio — ver Diseño técnico §2 del documento).

**Depende de:** nada nuevo.

## Alcance
- **Sí incluye:**
  - Migración: `app_user.country VARCHAR(100)` (nullable),
    `app_user.username VARCHAR(50)` (nullable), `UNIQUE (tenant_id,
    username)`.
  - `User.updateProfile(nombre, apellidos, country, username)` (o
    setters equivalentes) + validación de username (no vacío si se
    envía, unicidad por tenant con mensaje claro, no un 500 genérico por
    violación de constraint).
  - `PATCH /api/v1/account/profile` (autenticado vía JWT, mismo
    mecanismo que `SetPasswordController`): body
    `{nombre, apellidos, country, username}`, todos opcionales salvo
    nombre/apellidos (ya obligatorios en el dominio).
  - `GET /api/v1/account/profile` (o extender `UserResponse` ya
    existente) para que el frontend pueda leer los valores actuales.
- **No incluye:** el campo correo (flujo ya existente, sin cambios),
  avatar/foto (vive en galgoth-studio, ticket aparte).

## Criterios de aceptación (TDD)
- `PATCH` con datos válidos actualiza los 4 campos y los refleja en el
  `GET` subsecuente.
- Un `username` ya tomado por otro usuario del mismo tenant se rechaza
  con un error claro (`USERNAME_TAKEN` o similar), no un 500.
- Un `username` repetido en tenants DISTINTOS no choca (unicidad es por
  tenant).
- Sin `Authorization`, ambos endpoints responden `401`.
- Suite completa en verde.
- Verificación en vivo contra DEV.

## Hecho
- Migración `V12`: `app_user.country`/`app_user.username` (nullable) +
  `UNIQUE (tenant_id, username)`.
- `User.updateProfile(...)` + `AccountProfileService` (chequea unicidad
  de `username` antes de guardar, reutiliza `DuplicateIdentifierException`/
  `UserNotFoundException` ya manejadas por `GlobalExceptionHandler`).
- `GET`/`PATCH /api/v1/account/profile` (`AccountProfileController`,
  mismo mecanismo de auth que `SetPasswordController`). `UserResponse`
  gana `country`/`username`.
- `docs/API.md`/`docs/BASE_DE_DATOS.md` actualizados.
- Tests: `AccountProfileControllerTest` (real JWT, real DB, mismo patrón
  que `SetPasswordControllerTest`) — GET inicial, PATCH exitoso reflejado
  en GET, username duplicado en el mismo tenant rechazado sin sobrescribir,
  mismo username en tenant distinto sin conflicto, 401 sin auth. Suite
  completa en verde.
- **Verificación en vivo contra DEV**: pendiente (se completa tras el
  deploy de este PR).
