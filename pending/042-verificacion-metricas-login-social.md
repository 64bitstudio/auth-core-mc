# 042 — Verificación: métricas y auditoría con proveedores sociales reales

## Objetivo
Nace de `docs/definiciones/login-social-real.md` (OQ-10). `login_event.provider` ya es texto libre (sin migración necesaria) y el dashboard de métricas (ticket 028) ya sabe graficar por proveedor — este ticket es de **verificación**, no de construcción: confirmar que todo el camino de auditoría/métricas funciona de verdad con datos reales de `GOOGLE`/`FACEBOOK`, no solo `PASSWORD`.

**Depende de:** tickets 037 (login social real generando eventos) y idealmente 043 (credenciales reales configuradas, para poder generar logins de verdad en vez de solo simular el valor del campo).

## Criterios de aceptación (TDD)
- Confirmar que `SocialLoginSuccessHandler`/`SocialLoginFailureHandler` (ticket 037) registran `login_event` con `provider="GOOGLE"`/`"FACEBOOK"` reales en éxito y fallo, con el mismo detalle que ya registra el flujo de password (IP, timestamp, resultado).
- Verificado en vivo: al menos un login social exitoso real y uno fallido, confirmando en `/ui/admin/metrics` que la dona de éxito/fallo y la barra de actividad por proveedor (ticket 028) reflejan esos datos reales sin necesitar ningún cambio de código en `charts.js`/`admin-metrics.html`.
- Si algo NO funciona sin cambio de código (ej. algún caso no contemplado por la UI de métricas al ver un proveedor nuevo), documentarlo como hallazgo real y decidir si amerita un ticket aparte — no forzar un parche silencioso aquí.
- No se esperan tests nuevos si todo funciona como está diseñado (ticket de verificación, no de feature) — si aparece un gap real, sí se agregan tests para cerrarlo.
