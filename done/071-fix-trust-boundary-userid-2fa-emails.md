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
Implementado y mergeado en PR #131 (`fix/071-trust-boundary-userid-2fa-emails`), verificado en vivo en DEV.

- `EmailChangeController`/`TwoFactorController`: usuario exclusivamente
  del JWT verificado (`@AuthenticationPrincipal Jwt`), sin `userId` en el
  body. `TwoFactorController` ya no depende de
  `ClientContextResolver`/`TenantScopedUserResolver`.
- `JwtAudience` nuevo (helper compartido, evita triplicar el parseo del
  claim `aud` que ya usaba `AccountLinkProviderController`).
- `SecurityConfig`: `/api/v1/2fa/**` sale de `permitAll`; `/change-email`
  se acota a solo `/confirm`. `/verify-email/**` se queda completo,
  intacto -- decisión explícita (ver Objetivo).
- `TenantScopedUserResolver` sobrevive con su único caller restante
  (`EmailVerificationController`), Javadoc actualizado para reflejar que
  ya no es "temporal".
- `cuenta.html`: 6 llamadas (change-email + las 5 de 2FA) migran a
  `AuthCoreUi.callAuthenticated`; el reenvío de verificación se queda
  igual.
- `docs/API.md`/`docs/ARQUITECTURA.md` actualizados con el hallazgo
  completo y la razón de por qué `verify-email` se excluyó a propósito.
- Tests: `EmailChangeControllerTest`/`TwoFactorControllerTest`
  reescritos como end-to-end reales (Testcontainers, JWT real vía
  `DirectTokenService`) en vez de `@WebMvcTest` con el servicio mockeado
  -- ese patrón anterior era exactamente el que dejaba pasar el hallazgo
  sin que ningún test lo notara. `EmailVerificationControllerTest`
  conserva su suite original (comportamiento sin cambio).
- 2 hallazgos reales de Sonar en el primer CI (S1128 import sin uso,
  S8786 regex con backtracking súper-lineal en un test) -- ambos
  resueltos antes de mergear.
- Suite completa: 453 tests, 0 fallos, 0 errores.
- **Lado galgoth-studio** (PR #146 de ese repo, mergeado): `accountApi.requestEmailChange`
  se actualizó para el nuevo contrato (sin `userId`, Bearer real);
  `requestEmailVerification` no cambió, por diseño.
- Verificado en vivo: deploy a DEV de auth-core-mc exitoso tras el merge
  (commit `1a98f92`).

**Pendiente, fuera de alcance de este ticket**: el leak de UUID en
`ProjectSummary.avatarUrl` de galgoth-studio que hacía explotable la
cadena original sigue existiendo en ese repo (documentado, recomendado
revisar por separado) -- este ticket cerró el lado de auth-core-mc.
