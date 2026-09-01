# 050 — Portainer + subdominios públicos para SonarQube y el dashboard de Traefik

## Objetivo
Exponer por HTTPS público, con protección adicional, las tres
herramientas de infra que hoy solo son alcanzables por SSH/túnel o no
existen todavía: SonarQube (hoy solo `127.0.0.1:9000` en la VM), el
dashboard de Traefik (hoy sin exponer), y **Portainer** (herramienta
nueva, no instalada — dashboard web para gestionar Docker/Compose sin
CLI, adelantada en el roadmap de infra como "ticket nuevo después de
049/011", ver memoria `vm-deploy-infra-roadmap`).

Decisión tomada con Marco (2026-08-31, ronda de clarificación directa,
sin documento de definición formal — cambio bien entendido y ya
pre-aprobado en el roadmap): las tres quedan detrás de **Basic Auth de
nginx** además del login propio de cada herramienta — son las
superficies de mayor privilegio de la VM (Portainer en particular:
control equivalente a root sobre Docker), así que se protegen con una
capa extra frente a escáneres/bots de internet, sin restringir por IP
fija (Marco no quiere depender de una IP que puede cambiar).

## Alcance

**Incluye:**
- Instalar **Portainer CE** como contenedor nuevo en la VM (Docker
  Compose propio, `deploy/vm-infra/portainer/`), con acceso a
  `docker.sock` (mismo patrón de riesgo aceptado que ya tiene Jenkins —
  documentar explícitamente, no repetir la discusión de riesgo desde
  cero pero sí dejar la referencia).
- Exponer `sonarqube.64bitstudio.com` → SonarQube (agregar labels de
  Traefik + red `edge` al compose de SonarQube ya existente, sin quitar
  el bind a `127.0.0.1:9000` que sigue usando Jenkins/el runner
  internamente).
- Exponer `traefik.64bitstudio.com` → dashboard/API de Traefik
  (confirmar primero si el dashboard ya está habilitado en la config
  actual de Traefik; si el API no es de solo lectura, evaluar si hace
  falta `--api.dashboard=true` sin `--api.insecure` expuesto directo).
- Exponer `portainer.64bitstudio.com` → Portainer.
- **Basic Auth de nginx** (`auth_basic` + `htpasswd`, un solo usuario
  compartido está bien) en los 3 vhosts nuevos — no en `auth`/`jenkins`,
  que se quedan como están.
- 3 vhosts nuevos de nginx (mismo patrón que
  `deploy/vm-infra/nginx/{auth-core-mc,jenkins}.conf`) + certificados
  Let's Encrypt para los 3 subdominios nuevos (mismo patrón de
  `continue-on-error` mientras el DNS no exista, como ya se hizo con
  Jenkins).
- Extender el job `sync-vm-infra` de `.github/workflows/ci.yml` para
  mantener todo esto al día en cada push (mismo patrón ya establecido).
- Actualizar `docs/ARQUITECTURA.md`.

**No incluye:**
- Cambiar cómo Jenkins/el runner alcanzan a SonarQube internamente
  (sigue siendo `127.0.0.1:9000`/`sonarqube:9000` vía la red `vm-infra`,
  sin relación con el subdominio público nuevo).
- Migrar el modelo de secretos de despliegue (Vault) — fuera de alcance,
  ya descartado en el roadmap.
- El ticket gemelo de `mail-core-mc` (011) — esta infra es compartida de
  la VM, no específica de `auth-core-mc`, así que ese ticket la reusa
  cuando arranque, no la repite.

## Pendiente de una acción directa de Marco
- Crear los 3 registros DNS en Cloudflare: `sonarqube.64bitstudio.com`,
  `traefik.64bitstudio.com`, `portainer.64bitstudio.com` (mismo patrón
  que los subdominios existentes) — sin esto, el paso de Let's Encrypt
  queda con `continue-on-error` hasta que resuelvan.
- Elegir usuario/password del Basic Auth compartido (o confirmar que se
  genere uno y se le entregue).
- Login inicial de Portainer (usuario/password del primer arranque, que
  Portainer fuerza a crear en el primer acceso — como Jenkins, Marco
  debe completarlo a mano una vez).

## Criterios de aceptación
- Dado un request a cualquiera de los 3 subdominios nuevos sin
  credenciales de Basic Auth, entonces nginx responde 401 (no expone
  nada de la herramienta detrás).
- Dado un request con las credenciales de Basic Auth correctas,
  entonces se sirve la página de login propia de la herramienta
  (SonarQube/Traefik/Portainer), con TLS válido de Let's Encrypt.
- Dado que Marco completa el login inicial de Portainer, entonces puede
  ver los stacks/contenedores reales de la VM (dev/qa/prod, Jenkins,
  SonarQube, Traefik) sin usar la CLI de Docker.
- Dado un push cualquiera, el job `sync-vm-infra` mantiene esta infra
  nueva al día igual que ya hace con Traefik/SonarQube/Jenkins
  (idempotente, seguro de correr en cada push).
