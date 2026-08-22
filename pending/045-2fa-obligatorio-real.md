# 045 — 2FA obligatorio real (aplica a login con password y login social)

## Objetivo
Hallazgo del ticket 037: la definición de login social (OQ-8) asumía que existía un gate de 2FA obligatorio para reutilizar en el flujo social, pero `/api/v1/login` (password) hoy **no tiene ningún gate de 2FA** — `TwoFactorController`/`TwoFactorPreferenceService` son 100% autoservicio desde `/ui/cuenta` (el usuario puede habilitarlo y usarlo si quiere, pero nada lo obliga en el login). El login social (ticket 037) quedó consistente con ese comportamiento real (sin gate), confirmado explícitamente con el Product Owner en vez de inventar un gate nuevo solo para social.

Este ticket implementa el gate real, aplicándolo **a ambos flujos por igual** (password y social) — no algo exclusivo de login social.

**Depende de:** ninguno directamente, pero toca `SocialLoginSuccessHandler` (ticket 037) además de `AuthenticationService`/`DirectTokenService` (grant directo, ticket 007) — coordinar para no duplicar la lógica del gate entre ambos flujos.

## Criterios de aceptación (TDD)
- Un usuario con 2FA habilitado (`TwoFactorPreferenceService`, ya existente) que hace login con password (`/api/v1/login`) o con login social, no recibe tokens completos de inmediato — el flujo se detiene en un paso intermedio que exige el segundo factor (OTP o TOTP, según su preferencia ya configurada) antes de emitir la sesión final.
- Mecanismo compartido entre ambos flujos de entrada (password y social) — no dos implementaciones paralelas del mismo gate.
- Un usuario SIN 2FA habilitado no ve ningún cambio de comportamiento en ninguno de los dos flujos.
- Tests cubriendo: login con password + 2FA habilitado (bloqueado hasta completar el segundo factor), login social + 2FA habilitado (mismo comportamiento), y que ningún usuario sin 2FA ve fricción nueva.
- Documentar en `docs/API.md`/`docs/ARQUITECTURA.md` el contrato del paso intermedio (qué responde el backend cuando hace falta el segundo factor, cómo continúa el cliente).
