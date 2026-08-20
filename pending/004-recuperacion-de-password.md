# 004 — Recuperación de contraseña

## Objetivo
Flujo "olvidé mi contraseña" vía correo (Resend) o SMS (Twilio) según cómo se registró el usuario. Token de un solo uso, expiración parametrizable.

## Criterios de aceptación (TDD)
- Token de reset de un solo uso, se invalida tras usarse o expirar.
- No revelar si un email/teléfono existe o no en el sistema (respuesta genérica).
