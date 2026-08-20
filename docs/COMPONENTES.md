# Componentes de la interfaz — auth-core-mc

> Estructura de pantallas y componentes de la UI web. Se actualiza al completar el ticket `009-ui-web-login-y-theming` y cualquier ticket posterior de UI.

## Estado actual
**Pendiente de implementación.** La UI es una aplicación web (no React Native — ver `ARQUITECTURA.md` sección 8) que sirve las pantallas del flujo de autenticación.

## Pantallas planeadas

| Pantalla | Ruta | Qué hace |
|---|---|---|
| Login | `/login` | Formulario email/teléfono + password, botones de login social (solo los habilitados para el tenant) |
| Registro | `/register` | Formulario de alta: email o teléfono, password, nombre, apellidos |
| Verificar cuenta | `/verify-email` | Confirma el token recibido por correo |
| 2FA | `/2fa` | Solicita el código OTP/TOTP tras un login exitoso, si el usuario lo tiene activado |
| Olvidé mi contraseña | `/forgot-password` | Solicita el reset |
| Restablecer contraseña | `/reset-password` | Aplica la nueva contraseña con el token |
| Cambiar correo | `/change-email` | Solicita y confirma el cambio |

## Theming por tenant
Cada pantalla lee `app_name` y `primary_color` (y logo, cuando se defina el mecanismo de subida de assets) desde la configuración del tenant resuelta por el dominio/`client_id` de la petición. Ver `README.md` para dónde se edita esto en desarrollo local.
