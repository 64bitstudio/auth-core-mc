# 046 — UI de /ui/login para el 2FA obligatorio real

## Objetivo
Hallazgo flagged al cerrar el ticket 045 (2FA obligatorio real): `/api/v1/login` y `/api/v1/oauth2/social-exchange` ahora responden `202 TwoFactorRequiredResponse { twoFactorRequired: true, pendingToken, method }` en vez de tokens cuando el usuario tiene 2FA activo, pero `/ui/login` (ticket 039, ya en `main`) no interpreta esa respuesta — hoy un usuario real con 2FA activo queda varado sin ninguna pantalla que le pida el segundo factor.

Decidido explícitamente con el Product Owner: este ticket **no bloquea** el merge de 045 (que se mergea backend-only) — es seguimiento inmediato después, con el riesgo aceptado de que un usuario real con 2FA quede varado en `/ui/login` hasta que este ticket cierre.

**Depende de:** 045 (2FA obligatorio real — endpoint `POST /api/v1/login/2fa-verify` y contrato `202`, aún sin mergear a `main` al momento de crear este ticket) y 039 (plantillas actuales de `/ui/login`, ya en `main`).

## Criterios de aceptación (TDD)
- Al recibir `202 twoFactorRequired` de `/api/v1/login` o `/api/v1/oauth2/social-exchange`, `login.html`/el flujo de canje social muestra una pantalla intermedia con un campo para el código (OTP o TOTP, según `method` del response) y un botón que llama a `POST /api/v1/login/2fa-verify` con el `pendingToken`.
- **Reenvío de OTP**: para `method` = `OTP_EMAIL`/`OTP_SMS`, la pantalla intermedia muestra un botón "reenviar código"; no aplica para `TOTP` (no se muestra el botón).
- **Expiración del `pendingToken`** (TTL 5 min, ya fijado en 045): si el usuario intenta verificar después de expirado, se muestra un mensaje claro indicando que debe volver a iniciar sesión — no un error genérico ni una pantalla en blanco.
- Un usuario SIN 2FA sigue sin ver ningún cambio (login directo con `200`, sin pasar por esta pantalla).
- Cubre ambos orígenes: login con password y login social (mismo componente/paso intermedio para los dos, consistente con que el backend ya comparte el mecanismo).
- Revisión de accesibilidad del nuevo paso (consistente con la convención ya establecida en el proyecto — ver ticket 030).

## Hecho
