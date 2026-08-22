# 047 — Rate-limiting en TotpService.verify

## Objetivo
Hallazgo de seguridad flagged al cerrar el ticket 045 (2FA obligatorio real): `TotpService.verify` nunca tuvo límite de intentos propio, a diferencia de `OtpService.verifyOtp` (que sí reutiliza `LoginRateLimiter`). Hasta el ticket 045 esto vivía únicamente detrás del autoservicio de `/ui/cuenta`; con el gate obligatorio ahora queda expuesto, por primera vez, en el flujo principal de login (`POST /api/v1/login/2fa-verify`) — una superficie de fuerza bruta contra el código TOTP de 6 dígitos sin ninguna mitigación.

Decidido explícitamente con el Product Owner: reutilizar el mismo mecanismo/política que ya usa `OtpService.verifyOtp` vía `LoginRateLimiter` — consistencia entre ambos métodos de 2FA, sin introducir un componente ni una política de límites nueva.

**Depende de:** 045 (2FA obligatorio real — expone `TotpService.verify` en el flujo principal vía `TwoFactorLoginController`; hoy `TotpService.verify` ya existe desde el ticket 005).

## Criterios de aceptación (TDD)
- `TotpService.verify` aplica el mismo `LoginRateLimiter` (misma política de intentos/ventana) que ya usa `OtpService.verifyOtp` — sin duplicar lógica de rate-limiting, reutilizando el componente existente.
- Aplica por igual sin importar el punto de entrada: verificación desde `/ui/cuenta` (autoservicio, ya existente) y desde `POST /api/v1/login/2fa-verify` (ticket 045) comparten el mismo límite.
- Un usuario que excede el límite de intentos recibe el mismo tipo de rechazo (`TooManyAttemptsException` o equivalente) que ya ve hoy al abusar de `OtpService.verifyOtp`.
- Tests cubriendo: intentos fallidos repetidos bloqueados tras el límite, reseteo del contador tras un intento exitoso (si esa es la política ya vigente en `OtpService`), y que un usuario dentro del límite no ve fricción nueva.
- Documentar en `docs/ARQUITECTURA.md` (sección del ticket) que el hallazgo de seguridad reportado en el ticket 045 queda cerrado.

## Hecho
