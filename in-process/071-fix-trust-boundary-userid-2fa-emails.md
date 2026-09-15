# 071 — Corregir el "límite de confianza temporal" de /2fa y /change-email

## Objetivo
Hallazgo real de seguridad, encontrado al investigar cómo conectar login
social y 2FA en galgoth-studio (2026-09-15): `/api/v1/verify-email/request`,
`/api/v1/change-email/request` y toda la familia `/api/v1/2fa/**` confiaban
un `userId` mandado tal cual en el body, sin ninguna autenticación real —
un "límite de confianza temporal" aceptado desde antes del Authorization
Server real (ticket 007, `TenantScopedUserResolver.java`) y nunca migrado
después de que ese Authorization Server sí llegó.

La razón original ("adivinar un `userId` es molesto, no explotable, porque
completar el flujo exige poseer el correo/SMS de la víctima") dejó de
sostenerse en la práctica para 2 de los 3:

1. **Cadena real de secuestro de cuenta vía `change-email`**: el correo de
   confirmación se manda a la dirección NUEVA que el propio atacante
   elige, no a la real — el atacante posee ese "segundo factor" de forma
   trivial. Encadenado con que `ProjectSummary.avatarUrl` de galgoth-studio
   expone el UUID real del dueño de cualquier proyecto público en
   `GET /api/explore/projects` (sin sesión), esto permitía: filtrar el
   UUID de una víctima real → pedir cambio de correo → confirmar con el
   link que llega al correo del propio atacante (la víctima nunca recibe
   ningún aviso, `EmailChangeService` no notifica al correo viejo) →
   reset de contraseña con el correo nuevo → control total de la cuenta.
2. **`2fa/totp/enroll` no manda nada a ningún canal de la víctima en
   absoluto** — devuelve el secreto TOTP directo en la respuesta HTTP. Un
   atacante podía enrolar SU PROPIO secreto en la cuenta de otra persona y
   activarlo con `/2fa/method`, dejándola bloqueada afuera
   permanentemente sin su consentimiento y sin poseer nada que la víctima
   controlara.
3. **`verify-email/request` sí sostiene razonablemente la suposición
   original** (solo reenvía a la dirección YA registrada, cooldown de
   60s) — se queda tal cual, a propósito. Migrarlo además habría roto el
   único caller real que lo usa sin sesión: galgoth-studio dispara el
   primer correo de verificación justo después de `/api/v1/register`, que
   deliberadamente no entrega tokens (`PROP-GS-AUTH-01` Fig. 02) — no hay
   ningún JWT disponible en ese momento para exigir.

## Alcance
**Incluye:**
- `EmailChangeController`/`TwoFactorController`: el usuario sale
  exclusivamente del claim `sub` del JWT verificado
  (`@AuthenticationPrincipal Jwt`), nunca de un `userId` en el body —
  mismo patrón que `AccountProfileController`/`SetPasswordController` ya
  usan. `TwoFactorController` deja de necesitar
  `ClientContextResolver`/`TenantScopedUserResolver` en absoluto.
- `SecurityConfig`: `/api/v1/2fa/**` sale por completo de `permitAll`
  (cae bajo `anyRequest().authenticated()`); `/change-email/**` se acota
  a solo su ruta `/confirm` (la que de verdad es pública por diseño, el
  token emailado es su única credencial real). `/api/v1/verify-email/**`
  se queda completo en `permitAll`, sin cambio.
- `TenantScopedUserResolver` sobrevive con un solo caller restante
  (`EmailVerificationController`) — su Javadoc se actualiza para
  reflejar que ya no es "temporal", es la decisión final para ese
  endpoint puntual.
- `cuenta.html` (UI hospedada de auth-core-mc): las 6 llamadas afectadas
  (change-email + las 5 de 2FA) migran de `AuthCoreUi.call` a
  `AuthCoreUi.callAuthenticated` (mismo patrón que "Establecer
  contraseña" desde el ticket 041). El reenvío de verificación se queda
  como estaba.
- `docs/API.md`/`docs/ARQUITECTURA.md` actualizados.
- Lado galgoth-studio: `authApi.ts`/`accountApi.ts` — `requestEmailChange`
  deja de mandar `userId` y pasa a requerir el Bearer real;
  `requestEmailVerification` se queda exactamente como estaba (ver ticket
  propio de ese repo).

**No incluye:**
- `/verify-email/request` — se queda como estaba, ver razonamiento arriba.
- `/password-reset/request` — mecanismo distinto y correcto por diseño
  (manda el link a la dirección YA registrada, nunca a una que el
  llamador elija; además debe seguir sin distinguir "existe"/"no existe").
- El endpoint admin de alta de `identity_client`/promoción de rol (ticket
  053, pendiente aparte) — sin relación con este hallazgo.
- El leak de UUID en `ProjectSummary.avatarUrl` de galgoth-studio en sí —
  vive en ese repo; este ticket cierra el lado explotable de auth-core-mc,
  no ese leak (queda anotado, no es necesariamente un problema en sí
  mismo si el resto de la cadena está cerrada, pero vale la pena que
  Marco lo sepa).

## Criterios de aceptación (TDD)
- `change-email/request` y las 5 rutas de `/2fa/**` responden `401` sin
  un Bearer token válido — **ya no aceptan ningún `userId` en el body**
  (los DTOs correspondientes lo eliminaron por completo).
- `verify-email/request` sigue funcionando exactamente igual que antes
  (header `X-Client-Id` + `userId` en el body) — comportamiento sin
  cambio, cubierto por su suite de tests original.
- `change-email/request` con un JWT real manda la confirmación a la
  dirección nueva indicada, atribuida al usuario del JWT — nunca a uno
  que el caller pueda elegir vía un campo del body.
- `2fa/totp/enroll` con un JWT real persiste el secreto en la cuenta
  DUEÑA de ese JWT — verificado releyendo el usuario de la base real.
- `2fa/method` con `TOTP` sin haber enrolado antes responde
  `400 totp_not_enrolled` (comportamiento de negocio sin cambio).
- Suite completa en verde, con tests reales end-to-end (Testcontainers,
  JWT real vía `DirectTokenService`) para `EmailChangeController` y
  `TwoFactorController` — no mocks del servicio de dominio como antes.
- `cuenta.html` sigue funcionando de punta a punta (verificado en vivo).

## Hecho
