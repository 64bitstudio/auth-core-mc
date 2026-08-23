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
- **`TotpService.verify` reutiliza `LoginRateLimiter` tal cual** — mismo mecanismo que ya usa `OtpService.verifyOtp`, sin componente ni política nueva. `checkAllowed` corre antes de cualquier otra cosa (un usuario ya bloqueado ni siquiera llega a desencriptar el secreto); `recordFailure` en los dos rechazos reales (código fuera de ventana, código ya usado); `recordSuccess` (resetea el contador) solo en verificación exitosa.
- **Namespace de intentos propio** (`"totp:" + userId`, distinto de `"otp:" + userId` que ya usa `OtpService`) — un usuario con historial en ambos métodos tiene dos contadores independientes, cada superficie de adivinanza con su propio límite, sin interferir entre sí.
- **La comprobación de "no enrolado" queda fuera del rate-limit a propósito** — no es parte de la superficie de adivinanza real (solo ocurre por estado tamperado/inconsistente, nunca desde un flujo real).
- **Mismo límite en ambos puntos de entrada**, sin ningún cambio adicional de código: autoservicio (`/ui/cuenta`, ticket 005) y el flujo principal de login (`/api/v1/login/2fa-verify`, ticket 045/046) comparten el único `TotpService.verify` — un usuario que excede el límite en cualquiera de los dos ve el mismo `429 too_many_attempts` (reutilizando `GlobalExceptionHandler`, ya existente, sin cambios).
- **2 tests nuevos, 360/360 del proyecto en verde**: bloqueo real tras 5 intentos fallidos (`LoginRateLimiter.MAX_ATTEMPTS`), y que una verificación exitosa resetea el contador (no se acumula a través de un éxito). Sin gaps encontrados — el diseño existente de `OtpService`/`LoginRateLimiter` se trasladó tal cual.
- **Docs actualizadas**: `docs/API.md` (tabla de errores de `2fa-verify` corregida — el `429` ya no es "solo OTP"; nota de seguridad marcada como cerrada) y `docs/ARQUITECTURA.md` (sección "Ticket 047" + referencia cruzada desde la sección del ticket 045 + footer).
- **Sin Postman** — el proyecto sigue sin ninguna colección.
