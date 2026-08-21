# 004 — Recuperación de contraseña

## Objetivo
Flujo "olvidé mi contraseña" vía correo (Resend) o SMS (Twilio) según cómo se registró el usuario. Token de un solo uso, expiración parametrizable.

## Criterios de aceptación (TDD)
- Token de reset de un solo uso, se invalida tras usarse o expirar.
- No revelar si un email/teléfono existe o no en el sistema (respuesta genérica).

## Hecho (TDD real: rojo → verde)
- `SmsSender` (interfaz) + `TwilioSmsSender` (real, falla explícito sin credenciales) — nace aquí, reutilizable en ticket 005.
- `PasswordResetService.requestReset` diseñado para nunca lanzar excepción ni comportarse distinto entre identificador existente/inexistente — `/request` siempre responde `202`. Documentado como contraste deliberado con el ticket 003 (que sí puede revelar cooldown vía 429, porque ahí el llamador ya conoce el `userId`).
- Preferencia email > SMS cuando el usuario tiene ambos identificadores.
- 12 tests nuevos — 103/103 en verde en el proyecto completo, sin fixes de infraestructura esta vez (ya conocíamos los gotchas de Boot 4.1 de tickets anteriores).
