# 052 — Alta de tenant/cliente OAuth2 `galgoth-studio`

## Objetivo
`galgoth-studio` (core de software del ecosistema, ticket 035 de ese repo) necesita autenticar usuarios humanos contra `auth-core-mc`, pero no existía ningún `tenant`/`identity_client` para él en ninguno de los 3 ambientes — solo estaba el cliente de prueba `ticket-005-e2e-client`. Este ticket documenta el alta real hecha a petición directa de Marco (sin PR, ver nota de proceso abajo).

## Alcance
**Incluye:**
- Alta de un `tenant` + `identity_client` llamados `galgoth-studio` en DEV, QA y PROD.
- Cliente first-party (público, PKCE, sin `client_secret`) para el flujo `authorization_code` — mismo criterio que cualquier cliente de login humano existente (ticket 007).

**No incluye:**
- Nada del lado de `galgoth-studio` (su propio repo) — este ticket es solo el alta en `auth-core-mc`.
- Un endpoint de administración para dar de alta `identity_client` — no existía y sigue sin existir (ver hallazgo/ticket futuro 053).

## Criterios de aceptación (TDD)
- Existe una fila `identity_client` con `client_id='galgoth-studio'` en las 3 bases (dev/qa/prod), cada una apuntando a su propio `tenant` `galgoth-studio`.
- `is_first_party=true` en las 3 (login directo/PKCE habilitado, sin secreto).
- `redirect_uris` correcto por ambiente (dominio propio de cada uno, ver Hecho).
- Verificable leyendo de vuelta las filas (`SELECT ... JOIN tenant`) — no solo "debería haber corrido bien".

## Hecho
- **Nota de proceso:** este alta se ejecutó primero (a petición directa de Marco en sesión) y este ticket se escribe retroactivamente para dejarlo trazable — no siguió el flujo normal rama→PR→CI (regla 4 del equipo). Se documenta como excepción explícita, no como precedente: la próxima alta de cliente debería pasar por un ticket antes de tocar prod, o mínimo por el endpoint que propone el ticket 053.
- Insertado vía `ssh ampere-free` + `docker exec ... psql` directo contra el contenedor `postgres` de cada stack (`auth-core-mc-{dev,qa,prod}-postgres-1`) — mismo patrón manual que ya usa el ejemplo de `docs/README.md` y el sembrado de `mail-core-mc` en el ticket 048 (no hay endpoint admin para `identity_client`, solo para `tenant` vía `POST /api/v1/admin/tenants`, que además requeriría ya tener un usuario `PLATFORM_ADMIN` existente — no había uno disponible para usar ese camino).
- Mismo `tenant_id`/`client` (UUID) reusado en los 3 ambientes (bases independientes, sin conflicto entre sí) para facilitar correlación entre ambientes.
- Datos creados (iguales en los 3 ambientes salvo `redirect_uris`):
  - `tenant`: `name`/slug `galgoth-studio`, `app_name` "Galgoth Studio", `primary_color` `#48e5a0`, TTLs estándar iguales al único ejemplo ya documentado en `docs/README.md` (`access_token` 900s, `refresh_token` 2592000s, `email_verification` 86400s, `password_reset` 3600s, `otp` 300s).
  - `identity_client`: `client_id` `galgoth-studio`, `is_first_party=true` (sin `client_secret_hash`), `is_machine_client=false`/`scopes` default (`{openid,profile}`, sin tocar).
  - `redirect_uris` por ambiente: DEV `https://studio-dev.galgoth.64bitstudio.com/auth/callback`, QA `https://studio-qa.galgoth.64bitstudio.com/auth/callback`, PROD `https://studio.galgoth.64bitstudio.com/auth/callback`.
- Decisiones (slug/client_id, app_name, color, sufijo `/auth/callback` del redirect) confirmadas explícitamente por Marco vía preguntas antes de escribir en ninguna base — no asumidas.
- Verificado leyendo de vuelta la fila en los 3 ambientes (`SELECT ic.client_id, t.name, t.app_name, t.primary_color, ic.is_first_party, ic.redirect_uris FROM identity_client ic JOIN tenant t ON t.id = ic.tenant_id WHERE ic.client_id='galgoth-studio'`) — 1 fila en cada uno, con los valores esperados.
- **Hallazgo real que motivó el ticket 053** (mejora continua, regla 10 del equipo): no existe ningún endpoint admin para crear `identity_client` — toda alta de cliente (esta y la de `mail-core-mc` en el ticket 048) se hizo a mano por SSH, en cada ambiente por separado, sin auditoría ni validación de aplicación. Anotado como ticket futuro en vez de resolverlo aquí, para no mezclar alcance.
