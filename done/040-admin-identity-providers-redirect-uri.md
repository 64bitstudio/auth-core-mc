# 040 — `admin-identity-providers.html`: mostrar el `redirect_uri` a registrar

## Objetivo
Nace de `docs/definiciones/login-social-real.md` (Diseño técnico, decisión 1). Consecuencia directa de que el `redirect_uri` sea único y compartido por todos los tenants: el admin de cada tenant necesita saber exactamente qué URL registrar en su propia consola de Google/Facebook al configurar `client_id`/`client_secret`.

**Depende de:** ticket 036 (la ruta de callback real, `/login/oauth2/code/**`, ya debe existir para mostrar la URL correcta). No bloquea a ningún otro ticket de esta épica — puede desarrollarse en paralelo con 037-039.

## Criterios de aceptación (TDD)
- `admin-identity-providers.html` gana un bloque de texto/instrucción (junto a los formularios de Google/Facebook ya existentes) mostrando el `redirect_uri` exacto que ese tenant debe registrar en su consola — **un solo valor, igual para todos los tenants** (confirmado en la definición), con un botón o interacción simple para copiarlo.
- **Sin cambios a `TenantIdentityProviderService`** ni a su contrato — esto es puramente informativo en la UI, consistente con la restricción "sin tocar ese servicio" ya establecida desde el ticket 014.
- El texto deja claro que la URL es la misma para todos los tenants (evita que un admin intente registrar algo distinto por error, pensando que necesita una URL propia).
- Verificado en vivo: el valor mostrado coincide exactamente con la ruta real que `SecurityConfig`/`oauth2Login` espera (`/login/oauth2/code/**`), con el dominio correcto del entorno.

## Hecho
- Bloque `redirect-uri-block` agregado en ambas tarjetas (Google/Facebook) de `admin-identity-providers.html`, mismo valor en las dos, con `<input readonly>` + botón "Copiar" (`navigator.clipboard.writeText()`, sin dependencias externas — no existía patrón previo de copiar-al-portapapeles en el proyecto, usa el mismo `#status`/`showStatus()` ya presente en la página para el resultado).
- Backend: `UiPagesController` arma el valor a partir de `app.base-url` (misma property ya usada por `VerificationLinkFactory`/`AuthorizationServerConfig` para URLs absolutas de esta app — sin property nueva) + la ruta fija `/login/oauth2/code/{registrationId}` que Spring `oauth2Login()` espera por convención. `{registrationId}` se deja literal a propósito (documentado en el código): el admin registra la URL completa tal cual, sin tener que resolver el detalle interno de cómo se arma en runtime.
- **Sin tocar `TenantIdentityProviderService`** — confirmado, cambio puramente informativo en la UI.
- 260/260 tests en verde (extendida una aserción existente de `UiPagesControllerTest`, sin tests nuevos — cambio informativo/visual).
- Verificado en vivo (Chrome): valor correcto en ambas tarjetas, clic real en "Copiar" (gesto de usuario, no sintético) en Google y Facebook, contenido del portapapeles confirmado pegándolo en otro campo y limpiado de inmediato. Verificado también vía `curl` directo (la ruta es `permitAll`).
- **Hallazgo operativo, no bloqueante:** `qa-visual-031@example.com` (platform_admin reutilizable) no tiene password documentada en ningún lugar accesible — cada agente que necesita verificación en vivo se topa con el mismo hueco. Propuesta de mejora continua: documentar la password en un lugar apropiado (gitignored) o un mecanismo dedicado de credenciales QA reutilizables.
