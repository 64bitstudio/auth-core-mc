# auth-core-mc — Guía de inicio

Servicio centralizado de autenticación/autorización (OAuth2/OIDC), multi-tenant y clonable a instancia dedicada. Ver `ARQUITECTURA.md` para el porqué de cada decisión.

## Estado actual
Backend: modelo de dominio y migraciones (`001`), registro/login por password (`002`), verificación y cambio de correo (`003`), recuperación de contraseña (`004`), 2FA OTP+TOTP (`005`), configuración de login social por tenant (`006`), servidor de autorización OAuth2 con tokens reales (`007`), multi-tenencia probada + clonado a instancia dedicada (`008`), UI web server-rendered con theming (`009`), CI/CD con SonarQube+Telegram (`010`) — **todos los tickets del backlog inicial están en `/done`**. `/login` ya emite JWT + refresh token de verdad para clientes first-party; `/oauth2/authorize`+`/oauth2/token` (Authorization Code + PKCE) también funcionan para clientes third-party. Ya existe una UI real para registro/login/verificación/2FA/reset/cambio de correo (`/ui/**`) — lo que aún falta es integrarla con el flujo `/oauth2/authorize` de Spring Authorization Server y con el login social de Google/Facebook (ver `ARQUITECTURA.md`, ticket `009`).

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

## CI/CD (ticket `010`)
Cada push a cualquier rama (y cada PR) dispara `.github/workflows/ci.yml`: build + tests + análisis SonarQube (con Quality Gate real, el pipeline falla si queda en rojo) + notificación a Telegram (éxito o fallo).

**Corre en un self-hosted runner** registrado en esta Mac (`~/actions-runner-auth-core-mc`), no en un runner de GitHub en la nube — porque SonarQube vive en `http://localhost:9000` (`~/dev-infra`), inalcanzable desde la nube. Consecuencia: **el CI solo funciona mientras esta Mac esté encendida y despierta**. Ver `docs/ARQUITECTURA.md` (ticket `010`) para el razonamiento completo y las alternativas consideradas.

Para revisar o reinstalar el runner:
```bash
cd ~/actions-runner-auth-core-mc
./svc.sh status   # ver si está corriendo
./svc.sh stop      # detenerlo
./svc.sh start     # volver a arrancarlo
```
Si hay que registrarlo desde cero (otra máquina, o se perdió el registro), genera un token nuevo con `gh api -X POST repos/marco-cortes/auth-core-mc/actions/runners/registration-token --jq '.token'` y sigue la guía oficial de GitHub (Settings → Actions → Runners → New self-hosted runner) para descargar/configurar el paquete correspondiente a tu sistema operativo.

Los secretos del workflow (`SONAR_TOKEN`, `SONAR_HOST_URL`, `TELEGRAM_BOT_TOKEN`, `TELEGRAM_CHAT_ID`) viven en GitHub (Settings → Secrets → Actions de este repo), no en el código. `SONAR_TOKEN` se generó vía la API de SonarQube y también se guardó en `~/dev-infra/.env` para reusarse en futuros proyectos.

## Probar la UI web manualmente (ticket `009`)
Con el tenant/cliente `acme-local-dev` ya sembrado (ver arriba) y la app corriendo, abre en el navegador:
```
http://localhost:8080/ui/register?client_id=acme-local-dev
```
Regístrate, y quedarás en `/ui/cuenta` — desde ahí puedes reenviar el correo de verificación, cambiar tu correo, y probar 2FA (OTP o TOTP). `/ui/login?client_id=acme-local-dev` inicia sesión con una cuenta ya creada. Ver `docs/COMPONENTES.md` para el detalle de cada pantalla y por qué la "sesión" de `/ui/cuenta` vive en `sessionStorage`, no en el servidor.

## Clonar un tenant a su propia instancia dedicada (ticket `008`)
Si un tenant necesita aislamiento total (su propia base de datos, su propio contenedor — ver `ARQUITECTURA.md` decisión 1), el proceso es:

1. **Levanta la nueva instancia dedicada** (un `compose.yaml`/Postgres nuevo, vacío) y arranca la app una vez contra ella (`./gradlew bootRun` con las variables de conexión apuntando a esa base) para que Flyway cree el esquema (`V1__init.sql` + `V2__two_factor_method.sql`). Detén la app después — no hace falta que quede corriendo para este proceso.
2. **Exporta el tenant desde la instancia de origen** (compartida o donde sea que viva hoy):
   ```bash
   PGHOST=<host-origen> PGPORT=<puerto> PGUSER=auth_core_mc PGPASSWORD=... PGDATABASE=auth_core_mc \
     backend/scripts/export-tenant.sh <tenant_id> tenant-export.sql
   ```
3. **Impórtalo en la instancia dedicada** (la del paso 1, con el esquema ya creado y sin datos de ningún tenant todavía):
   ```bash
   PGHOST=<host-dedicado> PGPORT=<puerto> PGUSER=auth_core_mc PGPASSWORD=... PGDATABASE=auth_core_mc \
     backend/scripts/import-tenant.sh tenant-export.sql
   ```
4. Verifica (`psql` o un `SELECT` rápido) que el tenant, sus usuarios, `identity_client`(s) y `refresh_token`(s) llegaron — el import falla ruidosamente (violación de PK/UNIQUE) si la instancia destino no estaba realmente vacía, en vez de sobreescribir nada en silencio.
5. Apunta el DNS/config del cliente a la nueva instancia dedicada y, si el tenant deja de usarse en la instancia compartida, bórralo de ahí (`DELETE FROM tenant WHERE id = ...`, en cascada manual o vía las FKs — no hay todavía un endpoint de borrado, es una operación manual).

`export-tenant.sh`/`import-tenant.sh` solo necesitan `psql` en el PATH y las variables de conexión de libpq (`PGHOST`, `PGPORT`, `PGUSER`, `PGPASSWORD`, `PGDATABASE`) — funcionan igual contra un Postgres local en Docker, uno remoto ya tunelizado, o cualquier proveedor cloud futuro. Ver `docs/ARQUITECTURA.md` (ticket `008`) para el porqué de este diseño (incluyendo un bug real de sustitución de variables de `psql` encontrado solo al probarlo en vivo).

## Dónde modificar la personalización de un tenant (`primary_color`, `app_name`, etc.)
_Pendiente — se documentará con detalle exacto (endpoint y/o panel de administración) al completar el ticket `009-ui-web-login-y-theming`._ Por ahora, el diseño planeado es: estos valores viven en la tabla `tenant` (ver `BASE_DE_DATOS.md`) y se editan vía el endpoint de administración del tenant, no editando código.

## Estructura de carpetas de este repo
```
/pending      → tareas por hacer (una tarjeta .md por tarea)
/in-process   → tarea(s) en las que se está trabajando activamente
/done         → tareas completadas (historial)
/docs         → esta documentación, siempre actualizada al cerrar una tarea
```
