# auth-core-mc — Guía de inicio

Servicio centralizado de autenticación/autorización (OAuth2/OIDC), multi-tenant y clonable a instancia dedicada. Ver `ARQUITECTURA.md` para el porqué de cada decisión.

## Estado actual
Backend arrancado: modelo de dominio y migraciones (ticket `001`, ver `/done`). Aún no hay endpoints REST reales (eso empieza en el ticket `002`) ni UI.

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

## Dónde modificar la personalización de un tenant (`primary_color`, `app_name`, etc.)
_Pendiente — se documentará con detalle exacto (endpoint y/o panel de administración) al completar el ticket `009-ui-web-login-y-theming`._ Por ahora, el diseño planeado es: estos valores viven en la tabla `tenant` (ver `BASE_DE_DATOS.md`) y se editan vía el endpoint de administración del tenant, no editando código.

## Estructura de carpetas de este repo
```
/pending      → tareas por hacer (una tarjeta .md por tarea)
/in-process   → tarea(s) en las que se está trabajando activamente
/done         → tareas completadas (historial)
/docs         → esta documentación, siempre actualizada al cerrar una tarea
```
