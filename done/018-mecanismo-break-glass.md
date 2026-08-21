# 018 — Mecanismo de break-glass para acceso de emergencia

## Objetivo
Vía de acceso administrativo independiente del flujo normal de login (dogfooding, ticket 012), para cuando auth-core-mc esté degradado o caído y el equipo necesite diagnosticar/intervenir. Nace de `docs/definiciones/panel-administracion-clientes.md` (Riesgos y decisiones — dependencia circular).

## Criterios de aceptación (TDD)
- Endpoint de emergencia que NO depende de `AuthController`/OAuth2 — sigue funcionando si el flujo normal de auth tiene un incidente.
- Gateado por un secreto pre-compartido rotable (no hardcodeado — vía variable de entorno/gestor de secretos).
- Cada uso queda registrado con detalle (quién, cuándo, qué se hizo) — auditoría fuerte, dado que es una puerta de acceso de alto privilegio.
- Segundo factor / restricción adicional (allowlist de IP, TOTP, etc.) — detalle final a confirmar en el diseño de este ticket específico (pregunta abierta heredada de la definición).

## Hecho
- **Preguntas de diseño abiertas resueltas con el Product Owner**: segundo factor = allowlist de IP **y** TOTP **y** secreto compartido (los tres). Alcance v1 = diagnóstico + desactivar un tenant (no una intervención más amplia).
- `POST /api/v1/breakglass/diagnostics` y `POST /api/v1/breakglass/tenants/{id}/deactivate` — bajo `permitAll()` en `SecurityConfig`, sin pasar por `.oauth2ResourceServer(...)` ni ningún filtro de rol. Toda la autorización vive en `BreakGlassService` (secreto + TOTP + IP), sin tocar `AuthenticationService`/`DirectTokenService`/`JwtDecoder`.
- Nunca falla abierto: si falta configurar cualquiera de los tres factores, todo se rechaza.
- Comparación de secreto en tiempo constante (`MessageDigest.isEqual`).
- Cada uso (éxito o fallo) queda auditado en `break_glass_audit_event` (BD, best-effort) + log SLF4J siempre — la razón específica del fallo solo se guarda ahí, la respuesta HTTP es siempre un 401 genérico.
- Diagnóstico tolera un fallo de la propia base de datos (200 con `databaseHealthy: false`, no un 500).
- Prueba end-to-end real que nunca usa un token JWT/login en todo el archivo — prueba literal de la independencia respecto a `AuthController`/OAuth2.
- 250/250 tests en verde (238 antes de este ticket + 12 nuevos).

**Épica "panel de administración de clientes" completa** (tickets 011–018).
