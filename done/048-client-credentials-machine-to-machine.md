# 048 — Grant `client_credentials` para clientes machine-to-machine

## Objetivo
`mail-core-mc` (nuevo servicio del ecosistema, core de envío de correo) necesita autenticarse contra este servicio sin un usuario humano de por medio — su ticket 005 (resource server OAuth2, ver `docs/definiciones/mail-core-mc-v1.md` en ese repo) asumía que `auth-core-mc` ya soportaba `client_credentials`. Al ponerse a construir ese ticket se descubrió que no era así: este servicio solo tenía Authorization Code + PKCE (login interactivo) — `TenantAwareRegisteredClientRepository` hardcodeaba esos dos grants y los scopes `openid profile` para *todo* cliente, sin importar qué necesitara. Ya estaba anotado como "extensión futura" en `docs/BASE_DE_DATOS.md` desde el ticket 007; este ticket es esa extensión.

**Depende de:** 007 (`TenantAwareRegisteredClientRepository`, `identity_client`).

## Alcance
**Incluye:**
- Migración `V9` (aditiva): `identity_client.is_machine_client` (boolean, default `false`) y `identity_client.scopes` (text[], default `{openid,profile}`) — todo cliente existente conserva exactamente el comportamiento de hoy.
- `IdentityClient`: nuevo constructor (7 args) para clientes machine-to-machine; el constructor de 5 args existente sigue igual (delega con los defaults), cero cambios en los ~35 call sites existentes.
- `TenantAwareRegisteredClientRepository`: si `isMachineClient()`, registra solo el grant `CLIENT_CREDENTIALS` (no `AUTHORIZATION_CODE`/`REFRESH_TOKEN`) y usa `entity.getScopes()` en vez de los hardcodeados `openid`/`profile`.
- Sembrado un tenant "Plataforma (clientes m2m)" y el `identity_client` real de `mail-core-mc` (`is_machine_client=true`, scope `mail:send`) en la base de dev — sin endpoint de alta todavía (mismo estado que alta de tenants en general, ticket 008), sembrado a mano vía SQL.
- `docs/API.md`, `docs/BASE_DE_DATOS.md` actualizados.

**No incluye:**
- Endpoint de administración para dar de alta clientes machine-to-machine (ni de tenants/clientes en general — ticket 008, sin tocar).
- Persistir/rotar la llave de firma RSA (sigue regenerándose en cada arranque, limitación ya documentada y fuera de alcance de este ticket).
- Nada del lado de `mail-core-mc` (su propio ticket 005, repo aparte).

## Criterios de aceptación (TDD)
- Un `identity_client` existente (sin tocar sus filas) sigue viendo `is_machine_client=false` y `scopes=[openid,profile]` — cero cambio de comportamiento.
- Un `identity_client` con `is_machine_client=true` solo soporta el grant `client_credentials` (no `authorization_code`).
- Los scopes de un cliente machine-to-machine vienen de `entity.getScopes()`, no de los hardcodeados `openid`/`profile`.
- Todos los tests existentes (incluyendo los ~35 call sites de `new IdentityClient(...)`) siguen pasando sin modificación.
- Verificado en vivo (no solo con mocks): `POST /oauth2/token` con `grant_type=client_credentials` y las credenciales reales de `mail-core-mc` devuelve un access token real, verificable contra `/oauth2/jwks`; pedir un scope no autorizado responde `400 invalid_scope`; un secreto incorrecto no emite token.

## Hecho
- Migración `V9__identity_client_machine_scopes.sql`, puramente aditiva.
- `IdentityClient`: nuevo constructor de 7 args (`machineClient`, `scopes`); el de 5 args delega a este con `false`/`[openid,profile]` — los ~35 call sites existentes (tests, principalmente) no se tocaron.
- `TenantAwareRegisteredClientRepository.toRegisteredClient`: rama por `isMachineClient()` — `CLIENT_CREDENTIALS` solo, o `AUTHORIZATION_CODE`+`REFRESH_TOKEN` como antes; scopes desde `entity.getScopes()` en ambos casos (ya no hardcodeados).
- **5 tests nuevos** (3 unitarios en `TenantAwareRegisteredClientRepositoryTest`, 2 de persistencia con Testcontainers/Postgres real en `IdentityClientRepositoryTest`) — suite completa del proyecto sigue en verde (no se rompió ningún test existente).
- **Sembrado en dev**: tenant "Plataforma (clientes m2m)" + `identity_client` de `mail-core-mc` (`is_machine_client=true`, scope `mail:send`), secreto generado con `openssl rand`, hasheado con el mismo `Argon2PasswordEncoder` de la app (nunca en texto plano en la base — solo el hash).
- **Verificado en vivo de punta a punta** (no solo mocks): `POST /oauth2/token` con `grant_type=client_credentials` devolvió un access token real (`sub=mail-core-mc`, `scope=[mail:send]`, `kid` coincide con `/oauth2/jwks`). Pedir el scope `openid` (no autorizado para este cliente) → `400 invalid_scope`. Secreto incorrecto → `400 invalid_request`, sin token.
- **Hallazgo de infra, corregido en el camino (no rompe nada, documentado para quien lo vuelva a pisar):** Testcontainers no podía hablar con Docker localmente — `/var/run/docker.sock` es un symlink roto (apunta a un socket de una instalación vieja de Docker Desktop que ya no existe), y el socket real de OrbStack vive en otra ruta. Los tests con Testcontainers de este proyecto (`IdentityClientRepositoryTest` y similares) **fallan localmente sin `DOCKER_HOST=unix:///Users/marcocortes/.orbstack/run/docker.sock` explícito** — probablemente afecta a cualquier corrida local de estos tests, no solo los nuevos. No se tocó `/var/run/docker.sock` (requiere sudo, fuera de alcance de este ticket) — queda como nota para el Product Owner, ver también memoria persistente.
- **Quality Gate falló en el primer intento por algo curioso:** la regla `java:S1135` de Sonar ("Complete el TODO") es case-insensitive y matchea la palabra suelta "todo" — un comentario en español ("...false para *todo* lo existente...") disparó un falso "TODO pendiente". No es un problema de convención (ya hay comentarios en español en otros archivos del proyecto, ej. `SecurityConfig.java`), solo coincidencia con esa palabra puntual — se reescribió el comentario ("para cualquier cliente existente") y el Quality Gate pasó en el segundo intento.

