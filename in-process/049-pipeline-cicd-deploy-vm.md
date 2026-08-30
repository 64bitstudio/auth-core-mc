# 049 — Pipeline de CI/CD para desplegar auth-core-mc a la VM dedicada

## Objetivo
Llevar `auth-core-mc` de "solo CI en la Mac de Marco" a un despliegue
real en la VM dedicada (OCI Ampere A1.Flex, ya provisionada, acceso SSH
ya funcional), con entornos `test` y `prod` separados en la misma VM.
Decisiones tomadas junto al Product Owner (sesión 2026-08-29, sin
documento de definición formal — cambio bien entendido, resuelto por
ronda de clarificación directa). Ticket gemelo en `mail-core-mc` para lo
mismo; ambos comparten la VM pero cada proyecto tiene su stack de Docker
Compose aislado.

## REDISEÑO (2026-08-30, decisión de Marco — reemplaza el modelo de abajo)

El modelo `integracion`/`main` + GitHub Environment descrito en las dos
secciones siguientes **se implementó, se probó en vivo, y generó fricción
real** (2 PRs + 2 merges + 1 disparo manual por cada promoción a PROD) a
cambio de una protección que resultó no proteger nada (GitHub deja pasar
el gate de reviewer a cualquier admin del repo — `can_admins_bypass`, no
configurable — y Marco es admin). Se conserva el contenido original de
"Contexto y decisiones ya tomadas" y "Criterios de aceptación" tal cual
como historia, no como el diseño vigente.

**Diseño vigente**, decidido por Marco tras ver esa fricción:
- Tres ramas — `feature/NNN` → `dev` (merge automático cuando el pipeline
  queda verde) → `qa` (merge SIEMPRE manual de Marco) → `prod` (merge
  SIEMPRE manual de Marco). Sin GitHub Environment en ningún job — el
  merge manual de Marco a `qa`/`prod` ES el gate real.
- SonarQube se muda de la Mac a la VM (instancia nueva, sin migrar
  historial) y el runner self-hosted de la Mac se retira por completo —
  la Mac queda dedicada solo a codificar/commits/push.
- Ingress compartido con Traefik (`auth.64bitstudio.com`,
  `auth-qa.64bitstudio.com`, `auth-dev.64bitstudio.com`), TLS Let's
  Encrypt.
- Retención: dev=1, qa=1, prod=2 (antes test=1, prod=2).

Ver `docs/ARQUITECTURA.md`, sección "Ticket 049", subsección "Diseño
vigente (rediseño dev/qa/prod)" para el diseño completo, y "Diseño
original (histórico, reemplazado)" para el detalle de los 4 bloqueos
reales encontrados en el camino (ambigüedad de runners, `--env-file`
faltante, `IMAGE_TAG` como env var de paso, y el `can_admins_bypass` del
Environment).

## Contexto y decisiones ya tomadas — DISEÑO ORIGINAL, histórico, reemplazado por el REDISEÑO de arriba (no reabrir sin VoBo dedicado)

- **Cambio de flujo de ramas** (afecta la regla 4 del equipo — se
  documenta aquí explícitamente como cambio de proceso, no "de paso"):
  se introduce una rama persistente nueva `integracion` entre
  `feature/NNN` y `main`.
  - PR de `feature/NNN` → merge a `integracion` dispara: tests unitarios
    (`gradlew build`) → análisis SonarQube (ya existente) → build de
    imagen Docker → deploy automático al stack **TEST** en la VM.
  - `integracion` → merge a `main` dispara: promoción de la **misma**
    imagen (no rebuild, tag por SHA de commit) al stack **PROD** en la
    VM, gateada por aprobación manual (GitHub Environment con reviewer
    requerido — pausa el job hasta que el Product Owner apruebe).
- **Conexión con la VM**: runner self-hosted **nuevo**, registrado en la
  propia VM (systemd), mismo patrón que el runner de Sonar ya existente
  en la Mac. Sin llaves SSH en GitHub Secrets, sin exponer SSH para CI.
  Coordinar con el ticket gemelo de `mail-core-mc` para no registrar el
  runner dos veces (es la misma VM).
- **Sin registry externo**: build local al Docker daemon de la VM
  (descartado GHCR/Docker Hub a propósito — mismo host construye y
  despliega).
- **Retención de imágenes/recursos** (VM de capa gratuita — maximizar
  aprovechamiento de disco):
  - TEST: conserva solo la imagen actual (1 versión) — sin rollback
    prometido ahí.
  - PROD: conserva la imagen actual + la anterior (2 versiones) para
    poder hacer rollback manual.
  - Tras cada deploy (test y prod): limpieza total — borrar imágenes de
    release más allá del límite permitido, más `docker image prune -f`
    (dangling), `docker builder prune -f` (build cache), `docker
    container prune -f` (contenedores detenidos).

## Alcance

**Incluye:**
- `Dockerfile` para el backend (Spring Boot/Gradle) — no existe hoy.
- `docker-compose.test.yml` y `docker-compose.prod.yml` de despliegue
  (distintos del uso de Gradle/Sonar local que ya existe en CI).
- Modificación de `.github/workflows/ci.yml` (o workflow nuevo) con jobs
  `build-image` → `deploy-test` → `promote-prod` (gate manual).
- Script de limpieza de imágenes/recursos (retención 1 en test, 2 en
  prod, + prune general) corrido tras cada deploy.
- Configuración de branch protection para `integracion` y `main`.
- Registro/config del runner self-hosted nuevo en la VM (coordinado con
  el ticket gemelo de `mail-core-mc`).
- Actualizar `docs/ARQUITECTURA.md` con el nuevo flujo de ramas y de
  despliegue.

**No incluye:**
- Cambios de lógica de negocio del backend.
- El pipeline equivalente de `mail-core-mc` (ticket propio en ese repo).
- Cutover de proveedor de correo (eso es el ticket 008 de `mail-core-mc`,
  depende de que `mail-core-mc` esté en producción real primero).

## Criterios de aceptación (TDD) — DISEÑO ORIGINAL, histórico

- Dado un PR mergeado a `integracion`, cuando corre el workflow,
  entonces se ejecutan tests unitarios, SonarQube (con Quality Gate en
  verde), build de imagen, y el stack TEST en la VM queda corriendo la
  imagen nueva (verificable con `docker compose ps` y un healthcheck del
  endpoint real).
- Dado el stack TEST ya desplegado, cuando se hace merge de
  `integracion` a `main`, entonces el job de promoción a PROD queda
  pausado esperando aprobación manual (GitHub Environment) — no se
  despliega solo.
- Dado que se aprueba la promoción, cuando corre el job, entonces PROD
  queda corriendo la **misma** imagen (mismo tag/SHA) que se validó en
  TEST — no una reconstruida.
- Dado un segundo deploy a PROD, cuando termina, entonces solo existen 2
  imágenes de release para `auth-core-mc-prod` (la nueva y la anterior)
  y ninguna más vieja.
- Dado un deploy a TEST, cuando termina, entonces solo existe 1 imagen
  de release para `auth-core-mc-test`.
- Dado cualquier deploy (test o prod), cuando termina, entonces no
  quedan imágenes dangling, build cache acumulado, ni contenedores
  detenidos en la VM (verificable con `docker system df`).
- Dado el runner self-hosted de la VM, cuando se registra, entonces
  aparece como online en GitHub Actions y corre bajo un label distinto
  al runner de la Mac (para poder dirigir jobs a uno u otro).

## Criterios de aceptación (rediseño vigente)

- Dado un PR de `feature/NNN` con el pipeline completo en verde, entonces
  se auto-mergea a `dev` sin acción manual (sujeto a que Marco habilite
  `allow_auto_merge` en el repo — bloqueado para el agente por el
  clasificador de permisos, ver "Hecho").
- Dado un merge a `dev`, entonces corren tests + SonarQube (en la VM) +
  build de imagen + deploy al ambiente DEV, con healthcheck real.
- Dado que Marco mergea manualmente `dev` → `qa`, entonces se promueve
  (sin rebuild) la misma imagen validada en DEV al ambiente QA, con
  healthcheck real.
- Dado que Marco mergea manualmente `qa` → `prod`, entonces se promueve
  (sin rebuild) la misma imagen validada en QA al ambiente PROD, con
  healthcheck real — sin ningún gate automático adicional (el merge
  manual ES el gate).
- Dado un deploy a DEV o QA, cuando termina, solo existe 1 imagen de
  release para ese ambiente. Dado un deploy a PROD, solo existen 2
  (rollback manual).
- Dado cualquier deploy, no quedan imágenes dangling, build cache
  acumulado, ni contenedores detenidos.
- Dado un request a `auth[.-qa][-dev].64bitstudio.com`, entonces Traefik
  lo enruta con TLS válido al contenedor correspondiente (pendiente de
  verificar de punta a punta — requiere que Marco cree los registros DNS
  en Cloudflare, ver "Hecho").

## Hecho
