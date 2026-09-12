# 054 — CORS allowlist por ambiente

## Objetivo
`galgoth-studio` necesita que su frontend (SPA en el navegador) llame directo a la API JSON de auth-core-mc (`/api/v1/register`, `/api/v1/login`, etc.) desde un origen distinto. Verificado en vivo (`PROP-GS-AUTH-01`, sección 00 del blueprint): hoy auth-core-mc no tiene ninguna configuración CORS — un preflight real contra DEV no trae `Access-Control-Allow-Origin`, así que el navegador bloquearía la llamada. Decisión de Marco: allowlist exacta por ambiente, no un patrón amplio ni un proxy por el backend de galgoth-studio.

**Depende de:** ticket 052 (tenant/cliente `galgoth-studio` ya dado de alta en dev/qa/prod).

## Alcance
**Incluye:**
- `CorsConfigurationSource` nuevo, scopeado a `/api/v1/**` (nunca a `/oauth2/**`/`/ui/**` — el JWKS lo consume el backend de cada cliente server-to-server, nunca el navegador).
- Origen(es) permitidos configurables por variable de entorno (`CORS_ALLOWED_ORIGINS`, lista separada por comas — pensado desde ahora para más de un cliente futuro, no solo galgoth-studio), default vacío (= ningún origen permitido, mismo comportamiento que hoy — cambio aditivo).
- `deploy/docker-compose.{dev,qa,prod}.yml`: passthrough de la variable nueva.
- `deploy/.env.{dev,qa,prod}.example`: documentar la variable nueva.
- Real: cargar el valor real en `/home/ubuntu/secrets/auth-core-mc/.env.{dev,qa,prod}` de la VM (dominio de galgoth-studio de cada ambiente).
- `docs/API.md`/`docs/ARQUITECTURA.md` actualizados.

**No incluye:**
- Ningún cambio al login social (eso es el ticket 055).
- Autorizar el deploy a PROD por sí solo — sigue pasando por el gate manual de Marco en Jenkins, sin excepción.

## Criterios de aceptación (TDD)
- Una request con `Origin` igual al configurado recibe `Access-Control-Allow-Origin` con ese mismo valor.
- Una request con un `Origin` distinto (no configurado) NO recibe ese header.
- Con `CORS_ALLOWED_ORIGINS` vacío (default), ningún origen recibe el header — cero cambio de comportamiento para quien no lo configure.
- Verificado en vivo contra DEV (mismo preflight real que expuso el hallazgo): `OPTIONS /api/v1/login` con `Origin: https://studio-dev.galgoth.64bitstudio.com` trae el header; con otro origen, no.

## Hecho
- `CorsConfig` nuevo (`security/`): `CorsConfigurationSource` scopeado a `/api/v1/**`, origen(es) desde `CORS_ALLOWED_ORIGINS` (comma-separated, mismo criterio de parseo que `BreakGlassService.allowed-ips`), default vacío = ningún origen permitido.
- `SecurityConfig`: `.cors(...)` wireado antes de `authorizeHttpRequests`; agregado `requestMatchers(CorsUtils::isPreFlightRequest).permitAll()` para que un preflight a una futura ruta protegida no reciba 401 (hoy no es load-bearing — todo lo que un navegador necesita ya es `permitAll`).
- **Hallazgo real de dependencia**: `CorsUtils` no vive en `org.springframework.security.web.util.matcher` en esta versión (Spring Framework 7 / Spring Security 7.1) — se movió a `org.springframework.web.cors.CorsUtils`. Confirmado inspeccionando los jars reales (`spring-web-7.0.8.jar` la tiene, `spring-security-web-7.1.0.jar` ya no).
- `application.properties`: `app.cors.allowed-origins=${CORS_ALLOWED_ORIGINS:}`.
- `deploy/docker-compose.{dev,qa,prod}.yml` + `deploy/.env.{dev,qa,prod}.example`: passthrough documentado de la variable nueva.
- **6 tests nuevos**: `CorsConfigTest` (4, unitarios — parseo de la lista, default vacío, scope solo a `/api/v1/**`) + `CorsConfigurationIntegrationTest` (2, con Testcontainers reales — preflight real desde el origen permitido trae el header; desde uno no listado, **403 Forbidden** — no "200 sin header" como se describió al detectar el hallazgo original, porque una vez que existe un `CorsConfigurationSource` para la ruta, Spring Security rechaza de plano cualquier request con un `Origin` no permitido; una request sin header `Origin` en absoluto — cualquier llamada no-navegador — nunca pasa por ese chequeo). Suite completa: 375/375 en verde, sin tocar ningún test existente.
- **Verificado en vivo contra DEV, post-merge (PR #98) y redeploy automático confirmado** (`auth-core-mc-dev-app-1` reinició tras el merge a `dev`): mismo preflight real que expuso el hallazgo original al inicio de esta sesión.
  - Con `CORS_ALLOWED_ORIGINS=https://studio-dev.galgoth.64bitstudio.com` ya configurado en `/home/ubuntu/secrets/auth-core-mc/.env.dev` (agregado en esta sesión): `OPTIONS /api/v1/login` con `Origin: https://studio-dev.galgoth.64bitstudio.com` → `200` + `Access-Control-Allow-Origin: https://studio-dev.galgoth.64bitstudio.com`.
  - Mismo request con `Origin: https://attacker.example.com` → `403 Forbidden`, sin el header.
- **QA**: promovido `dev`→`qa` y verificado en vivo — mismo resultado exacto que DEV (`200`+header para el origen permitido, `403` para uno no listado).
- **PROD**: `CORS_ALLOWED_ORIGINS` cargado en el `.env.prod` real, deploy aprobado y ejecutado por Marco vía el gate manual de Jenkins, verificado en vivo — mismo resultado exacto que DEV/QA. Los 3 ambientes quedan con comportamiento idéntico.
- **PROD**: el valor (`https://studio.galgoth.64bitstudio.com`) **no se pudo configurar en esta sesión** — escribir en `/home/ubuntu/secrets/auth-core-mc/.env.prod` fue bloqueado explícitamente por el clasificador de permisos (mismo tipo de guardrail que ya bloqueó una escritura directa de PROD en `platform/done/005`). Marco necesita agregar esa línea él mismo (o autorizar explícitamente que se haga) antes de que el deploy a PROD (gate manual, sin cambios en esto) tenga efecto real — sin ese valor, el bean queda con el default vacío y CORS sigue sin funcionar ahí aunque el código ya esté desplegado.

