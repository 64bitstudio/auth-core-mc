# auth-core-mc — Guía de inicio

Servicio centralizado de autenticación/autorización (OAuth2/OIDC), multi-tenant y clonable a instancia dedicada. Ver `ARQUITECTURA.md` para el porqué de cada decisión.

## Estado actual
Backend: modelo de dominio y migraciones (`001`), registro/login por password (`002`), verificación y cambio de correo (`003`), recuperación de contraseña (`004`), 2FA OTP+TOTP (`005`), configuración de login social por tenant (`006`), servidor de autorización OAuth2 con tokens reales (`007`) — todos en `/done`. `/login` ya emite JWT + refresh token de verdad para clientes first-party; `/oauth2/authorize`+`/oauth2/token` (Authorization Code + PKCE) también funcionan para clientes third-party. Aún no hay UI ni el flujo de redirect+callback de Google/Facebook (ver `ARQUITECTURA.md`, ticket `009`).

## Requisitos
- Docker + Docker Compose (ya verificado en tu máquina)
- Java 21 o superior. En esta máquina solo hay JDK 25 instalado (también LTS) — el proyecto usa ese toolchain directamente (`backend/build.gradle`) para no depender de descarga automática de toolchains.
- Cuenta de GitHub con acceso al repo `auth-core-mc`
- **OrbStack debe estar corriendo** antes de compilar/testear — se suspende solo por inactividad y detiene los contenedores (Testcontainers, SonarQube). Si un `./gradlew test` falla con errores de conexión a Docker, corre `open -a OrbStack` y espera unos segundos antes de reintentar.

## Infraestructura compartida (ya levantada, fuera de este repo)
Este proyecto depende de servicios compartidos definidos en `~/dev-infra/docker-compose.yml` (no vive en este repo porque se reutiliza entre proyectos):
- **SonarQube**: `http://localhost:9000`
- **Notificaciones a Telegram**: `~/dev-infra/scripts/notify.sh`

## Cómo levantar el proyecto
1. Clonar este repo.
2. Asegúrate de que OrbStack (Docker) esté corriendo.
3. Entra a `backend/` y corre `./gradlew bootRun` — Spring Boot levanta automáticamente Postgres y Redis vía `backend/compose.yaml` (soporte nativo de Docker Compose de Spring Boot), corre las migraciones Flyway, y arranca la API en `http://localhost:8080`.
4. Para correr solo los tests: `cd backend && ./gradlew test` (usa contenedores Testcontainers efímeros, independientes de `compose.yaml`).
5. Para que `/verify-email` y `/change-email` envíen correos de verdad, exporta `RESEND_API_KEY` y `RESEND_FROM_ADDRESS` (cuenta de Resend) antes de `bootRun` — sin esto, el servicio arranca igual, pero cualquier intento de enviar un correo falla explícitamente (a propósito, ver `ARQUITECTURA.md`) en vez de fingir que se envió.
6. Para que la recuperación de contraseña por SMS (cuentas solo-teléfono) funcione de verdad, exporta `TWILIO_ACCOUNT_SID`, `TWILIO_AUTH_TOKEN` y `TWILIO_FROM_NUMBER` (cuenta de Twilio) — mismo contrato "falla explícito sin credenciales" que Resend.
7. Para producción, genera y exporta tu propia `APP_SECRET_ENCRYPTION_KEY` (`openssl rand -base64 32`) — el default en `application.properties` es **público** (vive en este repo) y solo sirve para desarrollo/tests; cualquier secreto cifrado con el default no ofrece protección real.
8. Credenciales de Google (`auth-core-mc`, proyecto GCP dedicado) y Facebook (`Auth Core MC`, app dedicada) ya están en `.env` — ambas apps quedaron en modo "prueba/desarrollo" (login solo para el desarrollador y testers agregados manualmente; publicarlas para cualquier usuario real es un paso aparte, ver consola de cada plataforma). El redirect URI configurado es `http://localhost:8080/login/oauth2/code/{google|facebook}`. Apple queda pendiente de que confirmes la membresía paga de Apple Developer Program.
9. **`compose.yaml` necesita su `name:` explícito** (ya lo tiene) si alguna vez tienes en esta máquina más de un proyecto cuyo backend viva en una carpeta llamada `backend` — sin ese campo, Docker Compose usa el nombre de carpeta como identificador de proyecto y puede confundir los contenedores de dos proyectos distintos (nos pasó en el ticket `007`, ver `ARQUITECTURA.md`).
10. **La clave RSA de firma de tokens se genera nueva en cada arranque** (`AuthorizationServerConfig`) — cualquier `accessToken` emitido antes de reiniciar el servicio deja de ser válido después. No apto para producción sin persistir y rotar la clave.

## Cómo probar `/register` y `/login` manualmente

Toda request necesita el header `X-Client-Id` (ver `API.md`). Como todavía no existe un mecanismo de alta de tenants/clientes (llega con ticket `008`), por ahora hay que insertar uno a mano para probar localmente:

```sql
INSERT INTO tenant (id, name, app_name, primary_color, access_token_ttl_seconds, refresh_token_ttl_seconds, email_verification_ttl_seconds, password_reset_ttl_seconds, otp_ttl_seconds)
VALUES ('11111111-1111-1111-1111-111111111111', 'Acme', 'Acme App', '#0057FF', 900, 2592000, 86400, 3600, 300);

INSERT INTO identity_client (id, tenant_id, client_id, is_first_party, redirect_uris)
VALUES ('22222222-2222-2222-2222-222222222222', '11111111-1111-1111-1111-111111111111', 'acme-local-dev', true, ARRAY['http://localhost:3000/callback']);
```

Luego:
```bash
curl -X POST http://localhost:8080/api/v1/register \
  -H "X-Client-Id: acme-local-dev" -H "Content-Type: application/json" \
  -d '{"email":"ada@example.com","nombre":"Ada","apellidos":"Lovelace","password":"abcd1234"}'

curl -X POST http://localhost:8080/api/v1/login \
  -H "X-Client-Id: acme-local-dev" -H "Content-Type: application/json" \
  -d '{"identifier":"ada@example.com","password":"abcd1234"}'
```

La respuesta de `/login` trae `tokens.accessToken` (JWT) y `tokens.refreshToken` (opaco). Para renovar o cerrar sesión (ticket `007`):

```bash
curl -X POST http://localhost:8080/api/v1/token/refresh \
  -H "X-Client-Id: acme-local-dev" -H "Content-Type: application/json" \
  -d '{"refreshToken":"<el refreshToken recibido>"}'

curl -X POST http://localhost:8080/api/v1/token/revoke \
  -H "X-Client-Id: acme-local-dev" -H "Content-Type: application/json" \
  -d '{"refreshToken":"<el refreshToken recibido>"}'
```

También puedes consultar la metadata OIDC estándar sin necesidad de `X-Client-Id`:
```bash
curl http://localhost:8080/.well-known/openid-configuration
curl http://localhost:8080/oauth2/jwks
```

## Dónde modificar la personalización de un tenant (`primary_color`, `app_name`, etc.)
_Pendiente — se documentará con detalle exacto (endpoint y/o panel de administración) al completar el ticket `009-ui-web-login-y-theming`._ Por ahora, el diseño planeado es: estos valores viven en la tabla `tenant` (ver `BASE_DE_DATOS.md`) y se editan vía el endpoint de administración del tenant, no editando código.

## Estructura de carpetas de este repo
```
/pending      → tareas por hacer (una tarjeta .md por tarea)
/in-process   → tarea(s) en las que se está trabajando activamente
/done         → tareas completadas (historial)
/docs         → esta documentación, siempre actualizada al cerrar una tarea
```
