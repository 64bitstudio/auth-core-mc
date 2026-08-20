# auth-core-mc — Guía de inicio

Servicio centralizado de autenticación/autorización (OAuth2/OIDC), multi-tenant y clonable a instancia dedicada. Ver `ARQUITECTURA.md` para el porqué de cada decisión.

## Estado actual
**El backend aún no tiene código** (estamos en la fase de backlog/arquitectura, ver `/pending`). Esta guía se irá completando con pasos reales conforme avancen los tickets `001` en adelante — por ahora documenta lo que ya existe: la infraestructura compartida.

## Requisitos
- Docker + Docker Compose (ya verificado en tu máquina)
- Java 21+ y Gradle (se añadirá al completar el ticket `001`)
- Cuenta de GitHub con acceso al repo `auth-core-mc`

## Infraestructura compartida (ya levantada, fuera de este repo)
Este proyecto depende de servicios compartidos definidos en `~/dev-infra/docker-compose.yml` (no vive en este repo porque se reutiliza entre proyectos):
- **SonarQube**: `http://localhost:9000`
- **Notificaciones a Telegram**: `~/dev-infra/scripts/notify.sh`

## Cómo levantar el proyecto (se completará al terminar el ticket `001`)
1. Clonar este repo.
2. Copiar `.env.example` a `.env` y completar credenciales (Resend, Twilio, Google/Facebook/Apple OAuth, clave de cifrado de secretos).
3. `docker compose up -d` (Postgres + Redis propios de este proyecto).
4. `./gradlew bootRun` (pendiente hasta ticket `001`).

## Dónde modificar la personalización de un tenant (`primary_color`, `app_name`, etc.)
_Pendiente — se documentará con detalle exacto (endpoint y/o panel de administración) al completar el ticket `009-ui-web-login-y-theming`._ Por ahora, el diseño planeado es: estos valores viven en la tabla `tenant` (ver `BASE_DE_DATOS.md`) y se editan vía el endpoint de administración del tenant, no editando código.

## Estructura de carpetas de este repo
```
/pending      → tareas por hacer (una tarjeta .md por tarea)
/in-process   → tarea(s) en las que se está trabajando activamente
/done         → tareas completadas (historial)
/docs         → esta documentación, siempre actualizada al cerrar una tarea
```
