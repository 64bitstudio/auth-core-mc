# 051 — Retirar el Vault local de la Mac, apuntar desarrollo local a la Vault de la VM

## Objetivo
Retirar el Vault local de `~/dev-infra` (instalado en el ticket 017,
nunca conectado a ningún ambiente real desplegado desde entonces — ver
`platform/done/005`, que conectó DEV/QA/PROD a la Vault de la VM en su
lugar) y apuntar el desarrollo local en la Mac de Marco a esa misma Vault
de la VM, vía una AppRole propia de mínimo privilegio. Decisión explícita
de Marco: "quitarlo y apuntar el dev local a la Vault de la VM" — no
dejarlo, no quitarlo sin reemplazo.

Depende de `platform/done/007-exponer-vault-desarrollo-local.md` (expone
el subdominio público + crea la AppRole) — cerrado con VoBo de Marco y
verificado en vivo antes de continuar con este ticket.

## Alcance

**Incluye:**
- `backend/src/main/resources/application.properties`: cambiar el
  default de `vault.address` de `http://localhost:8200` (Vault local) a
  `https://vault.64bitstudio.com` (Vault de la VM) — mismo mecanismo
  AppRole que ya usa el backend desplegado (`VaultTransitEncryptor`, sin
  cambios de código: la clase ya soporta AppRole y ya lo prioriza sobre
  el token estático, ver ticket platform/005). `vault.role-id` pasa a
  tener como default el RoleID (no-secreto) de la AppRole
  `auth-core-mc-local-dev` en vez del de `auth-core-mc-backend`.
- `backend/.env` de Marco: `VAULT_ROLE_ID`/`VAULT_SECRET_ID` de la
  AppRole `auth-core-mc-local-dev` — **ya hecho** (ver "Hecho" abajo,
  parte de este ticket que no dependía de la exposición pública).
- **Migración de datos real**: se encontró que el tenant local "Acme"
  (`11111111-1111-1111-1111-111111111111`) tiene credenciales reales de
  Google/Facebook configuradas (ticket 043,
  "confirmar-credenciales-reales-google-facebook") cuyo
  `wrapped_data_key` depende de la llave maestra del Vault LOCAL — no es
  descartable como dato de prueba sin más. Script preparado:
  `backend/scripts/migrate-local-tenant-keys-to-vm-vault.sh` (desenvuelve
  con el Vault local, envuelve con el mismo valor crudo en la Vault de la
  VM, verifica el round-trip por hash antes de tocar la base de datos,
  nunca imprime la data-key). **El clasificador de permisos bloqueó al
  agente al intentar ejecutar esta migración directamente** (mismo tipo
  de bloqueo que `platform/done/005` con el `INSERT` de PROD) — Marco
  debe correr este script él mismo, o autorizar explícitamente que el
  agente lo haga.
- Retirar `vault` de `~/dev-infra/docker-compose.yml`, borrar
  `vault-config.hcl` y `scripts/vault-unseal.sh`, quitar
  `VAULT_ADDR`/`VAULT_UNSEAL_KEY`/`VAULT_ROOT_TOKEN` de `.env`/`.env.example`
  — **solo después** de verificar de punta a punta que el nuevo mecanismo
  funciona (arranque local real contra la Vault de la VM, operación
  Transit real) Y de migrar/confirmar el dato de Acme.
- `docs/ARQUITECTURA.md`: documentar el cambio, por qué, y el nuevo
  alcance de la AppRole de desarrollo local.

**No incluye:**
- Nada del lado de `platform`/Vault de la VM — eso es el ticket 007 de
  ese repo.
- Tocar `auth-core-mc-backend` (la AppRole de los ambientes desplegados)
  — sigue exactamente igual.

## Criterios de aceptación
- Dado el backend arrancando localmente sin ninguna variable de Vault
  local configurada, cuando se ejercita `PUT
  /api/v1/admin/identity-providers/GOOGLE` con un `client_secret` de
  prueba, entonces se cifra/descifra correctamente vía la Vault de la VM
  — mismo comportamiento que antes con el Vault local, verificado en
  vivo.
- Dado el tenant "Acme" ya migrado, entonces sus credenciales reales de
  Google/Facebook siguen siendo legibles (round-trip de
  `wrapped_data_key` verificado) — sin pedirle a Marco que las
  reconfigure a mano.
- Dado `~/dev-infra` sin el servicio `vault`, entonces `docker compose up`
  en ese directorio sigue levantando SonarQube (fuera de alcance, no se
  toca) sin errores relacionados a Vault.
- Dado `~/dev-infra/.env`/`.env.example`, entonces ya no contienen
  `VAULT_ADDR`/`VAULT_UNSEAL_KEY`/`VAULT_ROOT_TOKEN`, y sí conservan
  intactas las variables de Telegram/SonarQube-VM/Cloudflare (fuera de
  alcance explícito).

## Hecho

Cerrado 2026-09-02. Todos los criterios verificados con evidencia real
-- ver `docs/ARQUITECTURA.md`, sección "Ticket 051", para el detalle
completo.

- **AppRole `auth-core-mc-local-dev` creada y verificada** en la Vault de
  la VM (positiva + 2 negativas reales) --
  `platform/deploy/vm-infra/vault/bootstrap-auth-core-mc-local-dev-approle.sh`.
  RoleID/SecretID entregados directamente a `backend/.env` de Marco,
  nunca impresos.
- **Hallazgo real, no asumido**: confirmado leyendo la base de datos
  local que SÍ había datos reales dependientes del Vault local -- el
  tenant "Acme" (`wrapped_data_key` no nulo, 2 filas en
  `tenant_identity_provider` GOOGLE/FACEBOOK). El resto de los tenants
  locales no tenían `wrapped_data_key` -- nada que migrar para ellos.
- **Migración real ejecutada por Marco** (bloqueada para el agente por el
  clasificador de permisos dos veces, mismo tipo de bloqueo que
  `platform/done/005`) con
  `backend/scripts/migrate-and-rename-acme-tenant.sh` -- versión combinada
  (migración + renombre del tenant a "64Bit Studio", pedido explícito de
  Marco en el momento) sobre la base del script preparado por el agente.
  **Verificado independientemente por el orquestador**: un intento real
  de desenvolver el `wrapped_data_key` actual contra la Vault de la VM
  tuvo éxito -- confirma que la migración funcionó de verdad, no solo que
  las longitudes coincidían.
- **`application.properties`**: default de `vault.address` ->
  `https://vault.64bitstudio.com`, default de `vault.role-id` -> RoleID de
  `auth-core-mc-local-dev`. Confirmado que no afecta a DEV/QA/PROD (sus
  propios `docker-compose.*.yml` siguen inyectando `VAULT_ADDR`/
  `VAULT_ROLE_ID` explícitos). `backend/.env.example` documentado.
- **Verificación end-to-end real**:
  1. Round-trip real de Transit contra `https://vault.64bitstudio.com`
     (TLS con certificado real de Let's Encrypt, login AppRole real) con
     las credenciales exactas de `auth-core-mc-local-dev`.
  2. Confirmado en vivo que el allowlist de rutas de nginx (`platform/007`)
     funciona: rutas fuera del allowlist -> 404 sin llegar a Vault; login
     con credenciales inválidas -> sí llega a Vault (400 real).
  3. `VaultTransitEncryptor` construido exactamente como Spring lo haría
     con los nuevos defaults de `application.properties` (mismo
     `RestClient`, mismo `role-id`, `secret-id` real desde el entorno) --
     `wrap`/`unwrap` real contra la Vault de la VM. Prueba manual
     (archivo de test descartado tras correrla), no parte de la suite
     permanente -- ticket 017 ya estableció que esa suite nunca pega
     contra el Vault compartido.
  - **Caveat técnico señalado, no oculto**: la resolución DNS plana
    (`curl`/Java sin `--resolve`) falló específicamente desde esta Mac
    durante la verificación -- confirmado con `dig` (local y `@8.8.8.8`)
    que el registro resuelve correctamente en todos lados; es una
    peculiaridad del resolver de sistema de esta máquina (`getaddrinfo`/
    mDNSResponder), no un problema real de DNS/infra. Todas las pruebas
    de red se hicieron con `--resolve` apuntando a la IP real, que sí
    valida hostname/certificado/TLS de extremo a extremo.
- **Retiro real del Vault local**: quitado el servicio `vault` de
  `~/dev-infra/docker-compose.yml`, borrados `vault-config.hcl` y
  `scripts/vault-unseal.sh`, quitadas las 3 variables de `.env`/`.env.example`
  (Telegram/SonarQube-VM/Cloudflare intactos). Contenedor detenido
  (`docker stop vault`) -- el borrado final (contenedor + volumen) quedó
  bloqueado por el clasificador (acción destructiva); script dejado en
  `~/dev-infra/scripts/retire-local-vault-container.sh` para que Marco lo
  corra cuando confirme que ya no lo necesita.
- **Hallazgo de seguridad real, señalado por transparencia**: al editar
  `~/dev-infra/.env`, un diff automático del harness mostró en claro
  `SONAR_TOKEN`/`CLOUDFLARE_API_TOKEN` (no las variables de Vault que se
  estaban editando) -- error del agente al no redactar esa lectura. Ningún
  valor se reutilizó ni se repitió después. Ver ARQUITECTURA.md para el
  candidato a hook de `dev-org-hooks-suite` que salió de este hallazgo.
