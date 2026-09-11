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
  lo enruta con TLS válido al contenedor correspondiente — **verificado
  de punta a punta para DEV y QA** (curl real desde afuera de la VM,
  200 + JSON de health real + certificado SAN válido de Let's Encrypt).
  PROD queda pendiente de que Marco decida promover `qa → prod` (esa
  promoción es exclusiva suya).

## Hecho

**Estado real al momento de escribir esto**: DEV y QA validados de punta
a punta de verdad (deploy real, healthcheck real, Traefik+TLS real desde
afuera de la VM, retención de imágenes real). PROD queda pendiente de que
Marco decida promover algo real vía `qa → prod` — esa promoción es
exclusiva suya, no se disparó ni se simuló. El ticket NO se mueve a
`done` todavía — pendiente de decisión conjunta con Marco.

### Migración a la organización GitHub `64bitstudio`
`auth-core-mc` y `mail-core-mc` migrados a `64bitstudio/*`, ambos
públicos. Runner self-hosted registrado a nivel de ORGANIZACIÓN
(`vm-oci-runner`, label `vm-oci`) — compartido de verdad entre ambos
proyectos, sin registrar dos veces. Runner group `Default` con
`allows_public_repositories: true` (decisión explícita de Marco, acepta
el riesgo de exponerlo a futuros repos públicos de la org).

### Diseño original (implementado, luego reemplazado — ver más abajo)
Ramas `integracion`(TEST)/`main`(PROD) + GitHub Environment con reviewer
requerido. Implementado completo: `Dockerfile` multi-stage,
`application-deploy.properties` (perfil `deploy` nuevo — hallazgo real:
el backend dependía enteramente de `spring-boot-docker-compose`,
excluido del jar empaquetado, para encontrar su Postgres/Redis),
`docker-compose.{test,prod}.yml`, `cleanup.sh` (retención 1/2),
`build-image`/`deploy-test`/`promote-prod` en `ci.yml`.

Bloqueos reales encontrados y resueltos en esta fase:
- `runs-on: self-hosted` ambiguo entre el runner de la Mac y
  `vm-oci-runner` (ambos con la label genérica "self-hosted") — fix:
  `runs-on: [self-hosted, macOS]`, confirmado contra la API real de
  runners, no asumido.
- `docker compose ... ps` (diagnóstico, `if: always()`) sin `--env-file`
  — rompía el job pese a que el deploy real ya había funcionado.
- `IMAGE_TAG` (env var de PASO de GitHub Actions, no heredada entre
  steps) faltante en esos mismos pasos de diagnóstico.
- GitHub Environment con "required reviewers": rechazado primero por la
  API (422, billing plan de repo privado); tras migrar a público SÍ se
  pudo configurar, pero en el primer `promote-prod` real **no se
  pausó** — `can_admins_bypass: true` (fijo, no configurable) deja pasar
  el gate a cualquier admin del repo, y Marco lo es. Este hallazgo (no
  un bug de config, una limitación real de la plataforma para equipos de
  una persona) fue la causa directa del rediseño de abajo.

### REDISEÑO dev/qa/prod (vigente) — decisión de Marco, 2026-08-30
Reemplaza el modelo de arriba. Implementado completo:
- Ramas renombradas vía API (`main`→`prod`, `integracion`→`dev`,
  preservando PRs abiertos y branch protection automáticamente); `qa`
  creada nueva. Las tres con el mismo branch protection (PR + check
  `build-test-analyze`, sin bloquear self-merge). Default branch del
  repo cambiado a `dev`.
- `docker-compose.{test,prod}.yml` → `docker-compose.{dev,qa,prod}.yml`
  (qa nuevo), cada app conectada a la red compartida `edge` con labels
  de Traefik. `cleanup.sh`: retención dev=1/qa=1/prod=2. `env-ctl.sh`
  para arranque/parada manual de dev/qa ("bajo demanda").
- SonarQube migrado de la Mac a una instancia nueva en la VM
  (`deploy/vm-infra/sonarqube/`, sin migrar historial). Runner de la Mac
  de auth-core-mc retirado por completo (servicio detenido + registro
  borrado por Marco vía API) — la Mac queda dedicada solo a
  codificar/commits/push. `ci.yml` reescrito: un solo runner
  (`[self-hosted, vm-oci]`) en todos los jobs, jobs `build-image`/
  `deploy-dev` (push a `dev`), `deploy-qa` (push a `qa`), `deploy-prod`
  (push a `prod`) — sin GitHub Environment en ninguno (no protegía nada
  real). Job nuevo `sync-vm-infra` (sin depender de `build-test-analyze`
  — es un prerrequisito, no una consecuencia) mantiene Traefik/SonarQube/
  red `edge` al día en cada push.
- Ingress compartido: Traefik puertas adentro
  (`127.0.0.1:8000`, sin TLS propio), **nginx de fábrica de la VM** como
  puerta de entrada pública en 80/443 (decisión de Marco: no
  deshabilitarlo) con reverse proxy hacia Traefik y terminación TLS real
  vía `certbot --nginx` (certificado SAN único para los 3 subdominios,
  Let's Encrypt, válido). DNS creados por Marco en Cloudflare.

**5 hallazgos reales de la verificación de punta a punta** (cada uno
solo visible corriendo el pipeline de verdad, ninguno visible en
revisión de código — ver detalle completo en `docs/ARQUITECTURA.md`
ticket 049):
1. `deploy/.env.dev`/`.env.qa` nunca existieron en la VM — el primer
   `deploy-dev` falló con `couldn't find env file`. Fix: bootstrap
   idempotente por CI (password vía `openssl rand`, nunca impreso).
2. El stack `auth-core-mc-test` del modelo anterior seguía corriendo,
   ocupando el puerto 8081 que ahora usa DEV — retirado (disposable por
   diseño, sin migrar datos). El viejo `auth-core-mc-prod` se dejó
   corriendo tal cual (mismo nombre/puerto, se actualiza solo).
3. `cleanup.sh` borró la imagen de release EN USO — dos imágenes de SHAs
   distintos con el mismo `CreatedAt` exacto (cache de capas de Docker
   sin cambios en `./backend`) rompían el ordenamiento por fecha. Fix:
   la imagen actual se resuelve del tag `:current`, más salvaguarda
   independiente (nunca borrar lo que un contenedor corriendo usa de
   verdad).
4. Traefik nunca pudo leer el provider Docker desde el día uno — API
   1.24 vs daemon 1.55 (bug conocido de Traefik con Docker Engine 29+,
   traefik/traefik#12253). Fijar `DOCKER_API_VERSION` a mano NO bastó;
   fix real: subir a `traefik:v3.7.12` (negociación automática de
   versión, traefik/traefik#12256).
5. El puerto 443 nunca estuvo abierto en el **Security List de OCI**
   (capa distinta del firewall local/iptables, que sí se había abierto
   antes) — solo tenía ingress para 22/80. Corregido vía
   `oci network security-list update`. Encontrado porque un `curl`
   hecho DESDE la propia VM contra su hostname público puede fallar por
   hairpin NAT aunque el puerto ya esté bien abierto — la prueba real
   tiene que hacerse desde afuera.

**Política de merges vigente**: `feature/NNN → dev` automático;
`dev → qa` lo mergea el orquestador (sesión principal), no este agente
ni Marco; `qa → prod` exclusivo de Marco, siempre.

**Verificado de punta a punta, desde afuera de la VM** (no solo
`localhost`/SSH): `curl https://auth-dev.64bitstudio.com/actuator/health`
→ `200`, `{"status":"UP",...}` real. Mismo resultado para
`auth-qa.64bitstudio.com` tras el merge de validación `dev → qa`
(PR #70). `qa → prod` deliberadamente no se disparó ni se simuló.

**Bloqueos del clasificador de permisos del harness** (para el registro,
no bloquearon el trabajo — cada uno se resolvió pidiendo la acción
directa a Marco/al orquestador en vez de rodearlo): `gh pr merge` (varias
veces), `gh workflow run` (disparo manual de `promote-prod` del modelo
viejo), `gh api -X PATCH` sobre `allow_auto_merge` y sobre el
runner-group, `DELETE` del registro del runner, `svc.sh uninstall` del
runner de la Mac, y un intento de commitear un step que deshabilitaba el
nginx de fábrica (Marco decidió no deshabilitarlo — ver rediseño).

**Candidato a mejora continua (regla 10 del equipo) — no implementado
aquí, solo anotado**: un preflight/hook que valide que ningún job
`self-hosted` sin label exclusivo pueda enrutarse al runner equivocado
(el hallazgo original de ambigüedad de runners, ya resuelto acá pero
podría repetirse en otro proyecto). Candidato para `dev-org-hooks-suite`.

**Nota de archivo (2026-09-02, movido a `done/`)**: todo lo de arriba
describe la orquestación ORIGINAL de este ticket, vía GitHub Actions —
sigue siendo el registro histórico real de cómo se resolvió, pero ya
**no** es la arquitectura vigente. El mismo día (2026-08-30) Marco pidió
mover la orquestación a **Jenkins** ("SEGUNDO PIVOTE", ver
`docs/ARQUITECTURA.md` de este repo para el detalle completo con
evidencia), y poco después la infra compartida completa (Jenkins,
Traefik, SonarQube, Portainer, Vault) se extrajo de este repo al repo
`64bitstudio/platform` (ver `platform/docs/ARQUITECTURA.md` y la
memoria `saas-paas-cores-strategy`), donde vive y se documenta desde
entonces. Este archivo se mueve a `done/` como cierre tardío (el
trabajo terminó hace días, solo faltaba el archivado) — para el estado
actual real de la infra, ver `platform/docs/ARQUITECTURA.md`, no este
archivo.
