# 062 — Sesiones activas (listar y revocar)

## Objetivo
Ver `docs/definiciones/perfil-de-usuario.md` de galgoth-studio (VoBo de
Marco recibido) — HU-6. `refresh_token` hoy no guarda nada de
dispositivo/navegador/actividad — decisión de Marco: dispositivo +
navegador + última actividad (parseado del `User-Agent`), sin
geolocalización de IP.

**Depende de:** nada nuevo.

## Alcance
- **Sí incluye:**
  - Migración: `refresh_token.user_agent TEXT` (nullable),
    `refresh_token.created_at TIMESTAMPTZ NOT NULL DEFAULT now()`,
    `refresh_token.last_used_at TIMESTAMPTZ NOT NULL DEFAULT now()`.
  - `AuthController`/`TokenController`: capturan el header `User-Agent`
    del request (nuevo parámetro `HttpServletRequest` o
    `@RequestHeader`) al emitir un refresh token (login) y lo pasan a
    `DirectTokenService`; cada `POST /api/v1/token/refresh` actualiza
    `last_used_at` del token usado.
  - `GET /api/v1/account/sessions` (autenticado): lista los refresh
    tokens no revocados/no expirados del usuario, con navegador/SO
    parseados del `User-Agent` en el momento de leer (no se guarda ya
    parseado — el dato crudo es la fuente de verdad), `createdAt`,
    `lastUsedAt`, y un flag `current` (comparando contra el token de la
    request actual).
  - `DELETE /api/v1/account/sessions/{id}` (revoca una individual, debe
    pertenecer al usuario autenticado — `404` si no, mismo criterio de
    no revelar existencia ajena) y
    `POST /api/v1/account/sessions/revoke-others` (revoca todas menos la
    actual).
- **No incluye:** geolocalización de IP/ciudad (decisión explícita de
  Marco), revocación automática al cambiar contraseña (ticket `061`,
  quedó como acción independiente).

## Criterios de aceptación (TDD)
- Login real desde dos "dispositivos" (dos `User-Agent` distintos en los
  tests) → `GET /sessions` devuelve ambos, cada uno con su
  navegador/SO correctos.
- La sesión de la request actual aparece marcada `current`.
- Revocar una sesión ajena (de otro usuario) → `404`.
- `revoke-others` deja solo la sesión actual usable (un refresh con
  cualquier otro token después responde inválido).
- Suite completa en verde.
- Verificación en vivo contra DEV: login real desde dos navegadores/
  clientes, confirmar que ambos aparecen y que revocar uno lo invalida.

## Hecho
