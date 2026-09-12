# 055 — Redirect configurable para login social (clientes con UI propia)

## Objetivo
`galgoth-studio` quiere el botón de login social (Google/Facebook) dentro de sus propias pantallas, con el usuario aterrizando de vuelta en su dominio al terminar — no en `/ui/social-callback` de auth-core-mc. Verificado en el código (`PROP-GS-AUTH-01`, sección 08.3): `SocialLoginSuccessHandler`/`SocialLoginFailureHandler` redirigen siempre a una ruta hardcodeada (`SOCIAL_CALLBACK_PATH`/`LOGIN_PATH`) — no a nada configurable por cliente, aunque `identity_client.redirect_uris` ya existe (sin usar para esto desde el ticket 052).

El tramo Google/auth-core-mc del flujo (consentimiento real + intercambio de código con el proveedor) no se mueve — solo cambia a dónde rebota auth-core-mc al terminar.

**Depende de:** ticket 052 (`redirect_uris` de `galgoth-studio` ya configurado). Relacionado con 037/038/039 (dueños originales de `SocialLoginSuccessHandler`/`FailureHandler`/`/ui/social-callback`).

## Alcance
**Incluye:**
- Migración aditiva: `identity_client.hosts_own_login_ui` (boolean, default `false` — ningún cliente existente cambia de comportamiento).
- `SocialLoginSuccessHandler`: si `hostsOwnLoginUi()`, redirige a `identityClient.getRedirectUris().get(0)` con `?code=...` en vez de a `/ui/social-callback`.
- `SocialLoginFailureHandler`: mismo criterio — si `hostsOwnLoginUi()`, redirige al `redirect_uri` del cliente con `?error=...` en vez de a `/ui/login`.
- `galgoth-studio` pasa a `hosts_own_login_ui=true` en dev/qa/prod (fila ya creada en el ticket 052 — solo se actualiza el flag).
- `docs/API.md`/`docs/BASE_DE_DATOS.md`/`docs/ARQUITECTURA.md` actualizados.

**No incluye:**
- Tocar `/api/v1/oauth2/social-exchange` (ticket 038) — el canje del código sigue exactamente igual, solo cambia quién lo llama (la página de galgoth-studio en vez de `/ui/social-callback`).
- Configurar credenciales reales de Google/Facebook para el tenant `galgoth-studio` (`tenant_identity_provider` sigue vacío hoy) — eso es una acción operativa aparte (reusar la app de Google de 64bitstudio, o crear una dedicada), no código.
- Nada del lado de galgoth-studio (construir la página que recibe `?code=`/`?error=` y llama al exchange) — eso es un ticket de ese repo, no de este.

## Criterios de aceptación (TDD)
- Un `identity_client` existente (sin tocar su fila) sigue viendo `hosts_own_login_ui=false` y el login social sigue aterrizando en `/ui/social-callback`/`/ui/login` — cero cambio de comportamiento.
- Un `identity_client` con `hosts_own_login_ui=true` y `redirect_uris` no vacío: tras un login social exitoso, el redirect final apunta a ese `redirect_uri` con `?code=` (no a `/ui/social-callback`); tras un fallo, apunta ahí con `?error=` (no a `/ui/login`).
- El código emitido sigue siendo de un solo uso, TTL 60s, vía el mismo `RedisTokenStore`/`EXCHANGE_PURPOSE` — sin mecanismo nuevo de emisión.
- Tests de integración con Testcontainers reales (mismo estándar que `SocialLoginSuccessHandler`/`FailureHandler` ya tienen desde el ticket 037).

## Hecho
- Migración `V10__identity_client_hosts_own_login_ui.sql`, puramente aditiva (`hosts_own_login_ui BOOLEAN NOT NULL DEFAULT false`).
- `IdentityClient`: nuevo constructor de 8 args (agrega `hostsOwnLoginUi`); el de 7 args (ticket 048) delega a este con `false` — los ~35+ call sites existentes no se tocaron. Nuevo `hostsOwnLoginUi()` getter.
- `SocialLoginSuccessHandler`: `successRedirect`/`redirectToError` (antes `redirectToThemedError`) ahora reciben el `IdentityClient` completo y ramifican por `hostsOwnLoginUi()` — si es `true` y hay `redirect_uris`, redirige ahí con `?code=`/`?error=`; si no, mismo comportamiento de siempre (`/ui/social-callback`/`/ui/login`).
- `SocialLoginFailureHandler`: mismo criterio en la rama de consentimiento denegado (`cancelledRedirect`) — la rama "sin correlación válida" (Decisión 4) queda intacta a propósito, nunca resuelve tenant.
- Fallback defensivo cubierto por test: `hosts_own_login_ui=true` con `redirect_uris` vacío no lanza excepción, cae al comportamiento de páginas hospedadas.
- `docs/API.md`/`docs/BASE_DE_DATOS.md` actualizados.
- **11 tests nuevos**: 3 en `SocialLoginSuccessHandlerTest` (redirect a `redirect_uri` propio en éxito, en error, y fallback cuando no hay `redirect_uris`), 1 en `SocialLoginFailureHandlerTest` (consentimiento denegado con UI propia), 2 en `IdentityClientRepositoryTest` (persistencia real del flag, default y explícito), 4 en `IdentityClientTest` nuevo (los 3 casos de `ownLoginUiRedirectUri()` a nivel de dominio, sin handlers ni Spring de por medio). Suite completa: 379/379 en verde.
- **Quality Gate real, hallazgo genuino (no falso positivo), 2 iteraciones**: el primer intento duplicaba la misma decisión "¿rebota a `redirect_uri` propio o a la página hospedada?" en 3 sitios (`SocialLoginSuccessHandler` dos veces, `SocialLoginFailureHandler` una). Primera corrección: extraída a `IdentityClient.ownLoginUiRedirectUri()` — el Quality Gate siguió en rojo (build #2). No pude confirmar el hallazgo exacto contra el dashboard de SonarQube (`sonarqube.64bitstudio.com` responde `401` tanto por nginx como por la API propia de Sonar; no hay token disponible en esta sesión ni en el Mac local — el MCP de SonarQube de esta sesión ya estaba fallando con "Not authorized" desde el inicio). Leí el log real de Jenkins por SSH ambas veces (`Quality gate is 'ERROR'` tras un análisis SonarQube exitoso — el análisis en sí nunca falló, solo el gate), pero ese log no lista los hallazgos individuales.
- **Segunda corrección (razonada, no confirmada contra el dashboard)**: aun tras extraer el método de dominio, las 3 llamadas seguían reconstruyendo el mismo `UriComponentsBuilder` alrededor de él — duplicación de estructura, no solo de la decisión. Extraído a `SocialLoginRedirect.buildUri(...)` (nueva clase, package-private, un único lugar) que los 3 sitios llaman ahora sin ninguna repetición.
- **2 tests nuevos** (`SocialLoginRedirectTest`) cubren `buildUri` directamente (cliente hospedado con `client_id`, cliente con UI propia sin `client_id`).
- **Tercer intento, también en rojo — esta vez confirmado en vivo por el log real de la Compute Engine de SonarQube (SSH, `ce.log`)**: `Load duplications | duplications=0` y `Persist issues | inserts=0` — cero duplicación, cero issues nuevos, y aun así el gate seguía en `ERROR`. Sin acceso al dashboard no pude ver la condición exacta; se lo pedí a Marco.
- **Hallazgo real, confirmado por Marco desde el dashboard**: `java:S107` — el constructor de 8 parámetros de `IdentityClient` (máx. 7 permitido). Tercera vez que este entity gana un flag opcional en 3 tickets seguidos (007→048→055) apilando parámetros posicionales — señal real de que tocaba resolver esto de raíz, no solo acortar.
- **Corrección de raíz**: reemplazado el constructor de 8 args por un `IdentityClient.Builder` (nested, con acceso directo a los campos privados — nunca pasa por un constructor ancho). Los constructores de 5 y 7 args (tickets 002/048) quedan intactos, sin romper ningún call site existente. Actualizados los ~6 call sites de test que usaban la forma de 8 args para usar el builder.
- Suite completa tras las 3 correcciones: 381/381 en verde.
- **Verificado en vivo en DEV y QA**: migración V10 aplicada (columna `hosts_own_login_ui` confirmada en ambos), `UPDATE identity_client SET hosts_own_login_ui = true WHERE client_id = 'galgoth-studio'` aplicado en ambos ambientes.
- **PROD**: deploy aprobado y ejecutado por Marco vía el gate manual de Jenkins; `hosts_own_login_ui=true` activado para `galgoth-studio` ahí también. Los 3 ambientes quedan con el mecanismo desplegado y activado.
- **Google configurado en los 3 ambientes** (fuera del alcance de este ticket, es dato/operación): reusando la app de Google de 64bitstudio (`client_id` confirmado, mismo en `backend/.env` local) — `PUT /api/v1/identity-providers/GOOGLE` llamado en dev/qa/prod con un usuario del tenant creado y eliminado solo para ese fin (limpiado después, sin dejar rastro).
- **Hallazgo real de autorización, encontrado al hacer esto**: `PUT /api/v1/identity-providers/{provider}` (ticket 006, no-admin) **no verifica rol** — cualquier usuario autenticado del tenant puede configurar/cambiar su login social, no solo un `TENANT_ADMIN`. Funcionó sin promover ningún usuario. Anotado como hallazgo para el ticket 053 (o uno nuevo) — no corregido aquí, fuera de alcance de este ticket.
- **Facebook configurado en los 3 ambientes** (mismo mecanismo que Google arriba): app distinta a la guardada en `backend/.env` local — Marco confirmó el `client_id`/`client_secret` correctos. `tenant_identity_provider` para `galgoth-studio` queda con `GOOGLE` y `FACEBOOK` ambos `enabled=true` en dev/qa/prod.
- **No configurado en esta sesión** (fuera del alcance de este ticket, es dato/operación, no código): las credenciales reales de Google/Facebook para el tenant `galgoth-studio` (`tenant_identity_provider` sigue vacío) — sin eso, el botón de login social no tiene con qué autenticar aunque el mecanismo de redirect ya funcione. `hosts_own_login_ui=true` para `galgoth-studio` se activa vía `UPDATE` una vez desplegada la migración en cada ambiente (mismo patrón manual que el ticket 052, anotado como parte del gap del ticket 053).

