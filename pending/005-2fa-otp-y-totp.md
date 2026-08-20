# 005 — 2FA: OTP por SMS/correo + TOTP

## Objetivo
Segundo factor de autenticación: OTP numérico enviado por SMS (Twilio) o correo (Resend), y TOTP compatible con Google Authenticator/Authy (RFC 6238).

## Criterios de aceptación (TDD)
- OTP de un solo uso, expiración parametrizable (default corto, ej. 5 min).
- Prevención de reuso de código TOTP dentro de la misma ventana (Redis).
- El usuario puede elegir/activar su método de 2FA preferido.
