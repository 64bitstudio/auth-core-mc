# 041 — HU-5: establecer password en una cuenta social-only

## Objetivo
Nace de `docs/definiciones/login-social-real.md` (HU-5) — agregado al alcance tras resolver OQ-5 con el Product Owner. Un usuario que se registró 100% vía login social puede establecer una contraseña desde `/ui/cuenta`, para también poder usar `/api/v1/login` directo.

**Depende de:** ticket 037 (necesita que existan cuentas creadas vía social para tener algo que probar) — pero es funcionalmente independiente del resto de la épica (no toca `oauth2Login`, `ClientRegistrationRepository` ni `external_identity`). Puede desarrollarse en paralelo con 038-040.

## Criterios de aceptación (TDD)
- `/ui/cuenta`: si la cuenta actual no tiene `password_hash` (social-only), se muestra una acción nueva "Establecer contraseña" — mismos requisitos de fortaleza que ya exige `/ui/register` (mínimo 8 caracteres, al menos una letra y un dígito).
- Al completar la acción, `app_user.password_hash` queda seteado (mismo hashing Argon2id que el resto del proyecto) — el usuario puede desde ese momento iniciar sesión indistintamente con password (`/api/v1/login`) o con cualquiera de sus proveedores sociales ya vinculados.
- Si la cuenta ya tiene `password_hash`, esta acción no se ofrece — la ruta existente de "cambiar contraseña" (si ya existe) sigue siendo la que aplica; no se duplica UI para el mismo propósito.
- No requiere reautenticación adicional más allá de la sesión ya activa (misma confianza que el resto de acciones de `/ui/cuenta`).
- Tests cubriendo: cuenta social-only estableciendo password por primera vez, intento de establecer password cuando ya existe una (debe rechazarse o redirigir al flujo de cambio, no crear un estado inconsistente), validación de fortaleza reutilizando la misma lógica de `/ui/register`.

## Hecho
- **Endpoint:** `POST /api/v1/account/password`, `SetPasswordService` + `SetPasswordController`. Rechaza con `PasswordAlreadySetException` (409 `password_already_set`) si ya existe `password_hash` — nunca sobreescribe silenciosamente.
- **Decisión de seguridad deliberada, documentada en el código:** en vez de copiar el patrón existente de `/api/v1/2fa`/`/api/v1/change-email` (`userId` enviado por el cliente + header `X-Client-Id`, sin verificación real de posesión — aceptable ahí porque completar esos flujos igual exige poseer el correo/SMS de la víctima), este endpoint usa el **Bearer JWT real** ya cableado en `SecurityConfig` (mismo mecanismo que protege el panel admin) — el `userId` sale del claim `sub` verificado, nunca del body. Establecer una password no tiene un segundo factor que mitigue un `userId` adivinado: surte efecto de inmediato. Sin infraestructura nueva — solo no se agregó esta ruta a `permitAll`.
- Validación de fortaleza y hashing reutilizados tal cual (`PasswordPolicy`, mismo `PasswordEncoder` Argon2id que `RegistrationService`/`PasswordResetService`) — cero lógica duplicada.
- `cuenta.html`: tarjeta "Establecer contraseña" oculta por defecto, visible solo si `!hasPassword` (campo nuevo en `UserResponse`, derivado de `password_hash != null`, nunca expone el hash). De paso corregido un bug de scope de `snapshot` ya existente en el script de esa página.
- Tests: `SetPasswordServiceTest` (unitario) y `SetPasswordControllerTest` (end-to-end real: HTTP + DB + JWT real, sin mocks) — éxito en cuenta social-only, rechazo sin sobreescribir cuando ya existe password (confirmando que el password original sigue funcionando), password débil con mensaje claro, rechazo sin Bearer token válido (401).
- Verificado en vivo (Chrome): cuenta social-only simulada (`password_hash = NULL` directo en un usuario de prueba, ya que el resto de la épica —038-040— no estaba mergeada al momento de este ticket) — tarjeta visible, submit funcional, mensaje de éxito, tarjeta se oculta sin recargar, login posterior con el password nuevo confirmado real (`200` en `/api/v1/login`), tarjeta nunca aparece con `hasPassword=true`, sin errores de consola.
- 3 violaciones nuevas de Quality Gate (SonarQube, `java:S5778`, lambdas de `assertThatThrownBy` con más de una invocación) encontradas y corregidas antes de abrir el PR — mismo patrón ya visto en el ticket 035.
- **Hallazgo de mejora continua, no bloqueante:** `/ui/cuenta` mezclaba dos modelos de confianza (client-supplied userId vs. JWT real) sin ninguna guía explícita de cuándo usar cada uno — vale la pena un criterio escrito (¿`ARQUITECTURA.md` o checklist de `qa-engineer`?) para que el próximo endpoint sensible de esa página no tenga que redescubrirlo desde cero.
- Detalle completo en `docs/ARQUITECTURA.md` (sección "Ticket 041").
