# 042 — Verificación: métricas y auditoría con proveedores sociales reales

## Objetivo
Nace de `docs/definiciones/login-social-real.md` (OQ-10). `login_event.provider` ya es texto libre (sin migración necesaria) y el dashboard de métricas (ticket 028) ya sabe graficar por proveedor — este ticket es de **verificación**, no de construcción: confirmar que todo el camino de auditoría/métricas funciona de verdad con datos reales de `GOOGLE`/`FACEBOOK`, no solo `PASSWORD`.

**Depende de:** tickets 037 (login social real generando eventos) y idealmente 043 (credenciales reales configuradas, para poder generar logins de verdad en vez de solo simular el valor del campo).

## Criterios de aceptación (TDD)
- Confirmar que `SocialLoginSuccessHandler`/`SocialLoginFailureHandler` (ticket 037) registran `login_event` con `provider="GOOGLE"`/`"FACEBOOK"` reales en éxito y fallo, con el mismo detalle que ya registra el flujo de password (IP, timestamp, resultado).
- Verificado en vivo: al menos un login social exitoso real y uno fallido, confirmando en `/ui/admin/metrics` que la dona de éxito/fallo y la barra de actividad por proveedor (ticket 028) reflejan esos datos reales sin necesitar ningún cambio de código en `charts.js`/`admin-metrics.html`.
- Si algo NO funciona sin cambio de código (ej. algún caso no contemplado por la UI de métricas al ver un proveedor nuevo), documentarlo como hallazgo real y decidir si amerita un ticket aparte — no forzar un parche silencioso aquí.
- No se esperan tests nuevos si todo funciona como está diseñado (ticket de verificación, no de feature) — si aparece un gap real, sí se agregan tests para cerrarlo.

## Hecho
Verificado en vivo con datos 100% reales (nunca simulados) — el camino completo de auditoría/métricas funciona tal como fue diseñado, **sin ningún cambio de código**.

- **Login social real exitoso con GOOGLE**: cuenta real del Product Owner, flujo completo `/ui/login` → consentimiento real de Google → `/ui/social-callback` → `/ui/cuenta`. `login_event` registrado con `provider='GOOGLE'`, `outcome='SUCCESS'`, `user_id` resuelto; `external_identity` vinculado con el `provider_user_id` real de Google.
- **Login social real exitoso con FACEBOOK**: mismo flujo completo, `login_event` con `provider='FACEBOOK'`, `outcome='SUCCESS'`; `external_identity` vinculado.
- **Login social real fallido con GOOGLE**: cancelación real en la pantalla de consentimiento de Google (con una segunda cuenta real que aún no había autorizado la app, para forzar la pantalla de consentimiento en vez de un login silencioso). Redirigió correctamente a `/ui/login?error=social_login_cancelled` con el mensaje themed ya construido por el ticket 039. `login_event` registrado con `provider='GOOGLE'`, `outcome='FAILURE'`, `user_id` nulo — mismo criterio ya documentado en el ticket 037/045 (el evento de fallo por consentimiento denegado se registra antes de resolver identidad, nunca hay un usuario que atribuir).
- **`/ui/admin/metrics` confirmado en vivo por el Product Owner, dos veces** (antes y después de agregar Facebook + el caso de fallo): la dona de éxito/fallo y la barra de actividad por proveedor (ticket 028) reflejan los cuatro eventos reales (`GOOGLE`×2 éxito, `FACEBOOK`×1 éxito, `GOOGLE`×1 fallo) correctamente, sin ningún cambio en `charts.js`/`admin-metrics.html`. Los criterios de aceptación de este ticket quedan cumplidos en su totalidad.
- **Sin tests nuevos** — ticket de verificación, ningún gap de código encontrado.

**Hallazgo de proceso, ya resuelto en el camino (no oculto):** `tenant_identity_provider` para el tenant Acme solo tenía `GOOGLE enabled=true` al empezar esta verificación — Facebook nunca se había guardado de verdad vía `PUT /api/v1/admin/identity-providers/FACEBOOK`, pese a que el ticket 043 tituló su cierre "confirma credenciales reales de Google/Facebook" (en realidad solo confirmó la consola de Meta y el `redirect_uri`, nunca persistió el secreto en la app). El Product Owner lo resolvió él mismo desde `/ui/admin/identity-providers` durante esta misma verificación — no amerita ticket aparte.

**Hallazgo de infra, ya resuelto en esta misma sesión (no exclusivo de este ticket, pero encontrado al levantar la app para esta verificación):** `bootRun` sin `VAULT_ADDR`/`VAULT_ROOT_TOKEN` en su entorno causaba un `500` real al iniciar cualquier flujo OAuth2 (no podía desencriptar el secreto del tenant); y por separado, Vault amaneció sellado tras un reinicio del contenedor (`Vault is sealed`, otro `500`). Resuelto exportando esas dos variables desde `~/dev-infra/.env` (autorizado explícitamente por el Product Owner) y corriendo `~/dev-infra/scripts/vault-unseal.sh` (script ya existente del ticket 017) — ambos pasos ya forman parte del arranque normal de un entorno de desarrollo fresco, documentados aquí para que el próximo ticket que necesite levantar la app no repita el mismo tropiezo.
