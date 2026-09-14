# 061 — Cambiar contraseña (autenticado, con contraseña actual)

## Objetivo
Ver `docs/definiciones/perfil-de-usuario.md` de galgoth-studio (VoBo de
Marco recibido) — HU-5. Hoy existen `SetPasswordService` (establecer una
contraseña en una cuenta social-only que no tiene ninguna) y
`PasswordResetService` (olvidé mi contraseña, sin sesión). Falta el
tercer caso: un usuario CON sesión y CON contraseña que quiere
cambiarla, confirmando la actual.

**Depende de:** nada nuevo.

## Alcance
- **Sí incluye:**
  - `ChangePasswordService`/`POST /api/v1/account/change-password`
    (autenticado vía JWT): body `{currentPassword, newPassword}`.
    Verifica `currentPassword` contra el hash real
    (`PasswordEncoder.matches`), valida `newPassword` con
    `PasswordPolicy` (misma que el resto del proyecto), actualiza el
    hash.
  - Cambiar contraseña NO revoca las demás sesiones automáticamente
    (decisión explícita del documento — son acciones independientes).
  - Si el usuario no tiene contraseña (social-only), este endpoint
    responde un error claro indicando que debe usar
    `POST /api/v1/account/set-password` (ya existente) en su lugar, no
    un error genérico.
- **No incluye:** revocar sesiones (ticket `062`), el flujo de "olvidé
  mi contraseña" (sin cambios).

## Criterios de aceptación (TDD)
- Contraseña actual correcta + nueva válida → `200`, el hash cambia
  (login con la vieja deja de funcionar, con la nueva sí).
- Contraseña actual incorrecta → rechazado con mensaje claro, sin
  revelar más que eso.
- Nueva contraseña que no cumple la política → rechazada
  (`weak_password`, mismo código que el resto del proyecto).
- Un usuario social-only (`passwordHash == null`) recibe un error
  distintivo, no un `NullPointerException`/500.
- Sin `Authorization` → `401`.
- Suite completa en verde.
- Verificación en vivo contra DEV.

## Hecho
- `ChangePasswordService` + `PATCH /api/v1/account/password` (no `POST
  /change-password` como decía el borrador inicial del ticket — mismo
  path que `SetPasswordController`, distinto verbo HTTP: `POST` =
  primera vez, `PATCH` = cambio; evita dos rutas para el mismo recurso).
- `IncorrectCurrentPasswordException` (401, distinta de
  `InvalidCredentialsException` — esa es específicamente para no revelar
  detalles de un intento de LOGIN anónimo, no aplica aquí) y
  `NoPasswordSetException` (409, espejo de `PasswordAlreadySetException`)
  nuevas, mapeadas en `GlobalExceptionHandler`.
- No revoca otras sesiones (decisión del documento, acción
  independiente — ticket `062`).
- `docs/API.md` actualizado.
- Tests: `ChangePasswordControllerTest` (real JWT/DB) — cambio exitoso
  (login viejo falla, nuevo funciona), contraseña actual incorrecta
  rechazada sin cambiar nada, nueva contraseña débil rechazada, cuenta
  social-only recibe `no_password_set`, 401 sin auth. Suite completa en
  verde.
- **Verificación en vivo contra DEV**: pendiente (se completa tras el
  deploy de este PR).
