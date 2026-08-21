# 005 — 2FA: OTP por SMS/correo + TOTP

## Objetivo
Segundo factor de autenticación: OTP numérico enviado por SMS (Twilio) o correo (Resend), y TOTP compatible con Google Authenticator/Authy (RFC 6238).

## Criterios de aceptación (TDD)
- OTP de un solo uso, expiración parametrizable (default corto, ej. 5 min).
- Prevención de reuso de código TOTP dentro de la misma ventana (Redis).
- El usuario puede elegir/activar su método de 2FA preferido.

## Hecho (TDD real: rojo → verde)
- `V2__two_factor_method.sql` — nueva migración (no se toca V1, son inmutables).
- `SecretEncryptor` (AES-256-GCM) — pieza que faltaba desde ticket 001 para que `totp_secret_encrypted` fuera realmente cifrado. Reutilizable en ticket 006.
- `Totp` (RFC 6238, sin librería externa) — Base32 + HMAC-SHA1 implementados a mano.
- `OtpService` — códigos de 6 dígitos, TTL del tenant, cooldown de reenvío de 30s, un solo uso. Reutiliza `LoginRateLimiter` (ahora con constantes públicas) para bloquear fuerza bruta contra el código.
- `TotpService` — enroll/verify, protección anti-reuso de ventana vía Redis (independiente de la tolerancia de ±1 ventana por desfase de reloj).
- `TwoFactorPreferenceService` — activar el método preferido; rechaza activar TOTP sin enrollment previo (`TotpNotEnrolledException`).
- Endpoints: `/2fa/otp/request`, `/2fa/otp/verify`, `/2fa/totp/enroll`, `/2fa/totp/verify`, `/2fa/method`.
- 45 tests nuevos — 136/136 en verde, sin fixes de infraestructura (todos los gotchas de Boot 4.1 ya se conocían de tickets previos).
