# auth-core-mc — Guía de inicio

Servicio centralizado de autenticación/autorización (OAuth2/OIDC), multi-tenant y clonable a instancia dedicada. Ver `ARQUITECTURA.md` para el porqué de cada decisión.

## Estado actual
Backend: modelo de dominio y migraciones (`001`) + registro/login por password (`002`), ambos en `/done`. Aún no hay UI ni tokens OAuth2 reales (ticket `007`).

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
5. (Pendiente, llega con tickets `003`/`005`/`006`): copiar `.env.example` a `.env` y completar credenciales (Resend, Twilio, Google/Facebook/Apple OAuth, clave de cifrado de secretos).

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

## Dónde modificar la personalización de un tenant (`primary_color`, `app_name`, etc.)
_Pendiente — se documentará con detalle exacto (endpoint y/o panel de administración) al completar el ticket `009-ui-web-login-y-theming`._ Por ahora, el diseño planeado es: estos valores viven en la tabla `tenant` (ver `BASE_DE_DATOS.md`) y se editan vía el endpoint de administración del tenant, no editando código.

## Estructura de carpetas de este repo
```
/pending      → tareas por hacer (una tarjeta .md por tarea)
/in-process   → tarea(s) en las que se está trabajando activamente
/done         → tareas completadas (historial)
/docs         → esta documentación, siempre actualizada al cerrar una tarea
```
