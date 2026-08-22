# 044 — Fix: `admin-identity-providers.html` muestra el `redirect_uri` sin resolver

## Objetivo
Hallazgo real encontrado al intentar registrar el `redirect_uri` de verdad en Google Cloud Console (paso operativo del ticket 043): Google exige coincidencia **exacta** del `redirect_uri` — sin plantillas ni wildcards. El ticket 040 (ya mergeado) le muestra al admin el valor con `{registrationId}` **literal, sin resolver** (`http://localhost:8080/login/oauth2/code/{registrationId}`). Si un admin copia ese valor tal cual a la consola de Google/Facebook, nunca va a hacer match contra la request real (que usa el `registrationId` ya resuelto, ej. `.../code/3fa85f64-...::google`) — el login social fallaría siempre con `redirect_uri_mismatch`, para todos los tenants.

La causa NO es un bug en `TenantAwareClientRegistrationRepository` (ticket 036) — es correcto y esperado que cada tenant tenga su propio valor exacto de `redirect_uri` para registrar, porque cada tenant ya tiene su propio client OAuth (`TenantIdentityProvider`) en Google/Facebook. El bug es que `admin-identity-providers.html`/`UiPagesController` (ticket 040) construyen el valor con el placeholder crudo en vez de resolverlo con el `identityClientId` real del tenant que se está configurando.

**Depende de:** ticket 040 (ya mergeado, es lo que se corrige). Bloquea la verificación en vivo real del ticket 043 (registrar el valor correcto en las consolas de Google/Facebook) y del resto de la épica de login social.

## Criterios de aceptación (TDD)
- `UiPagesController.adminIdentityProviders(...)` resuelve el `IdentityClient` real del tenant actual (ya se resuelve el tenant en ese método vía `client_id`) y arma el `redirect_uri` con el `registrationId` ya resuelto por proveedor: `{app.base-url}/login/oauth2/code/{identityClientId}::google` y `.../{identityClientId}::facebook` — dos valores reales, uno por tarjeta, no un único valor genérico compartido.
- El texto explicativo en `admin-identity-providers.html` se ajusta: ya no dice "el mismo valor para todos los tenants" (esa premisa era incorrecta) — aclara que es el valor específico de **este** cliente/tenant, y que cambiará si el tenant se recrea (nuevo `IdentityClient`, UUID distinto).
- `docs/definiciones/login-social-real.md` (Decisión 1) se corrige/anota para reflejar el comportamiento real: la plantilla de ruta es la misma para todos los tenants, pero el valor concreto a registrar en cada consola de proveedor es único por tenant (contiene su propio `identityClientId`).
- Verificado en vivo: el valor mostrado para el tenant Acme coincide con lo que `TenantAwareClientRegistrationRepository`/`SocialLoginSuccessHandler` (ticket 036/037) realmente esperan y generan — confirmado registrándolo en la consola real de Google (ticket 043) y completando un login social de prueba de principio a fin.
- Tests existentes de `UiPagesControllerTest` actualizados para la nueva aserción (valor con UUID real, no placeholder).

## Hecho
- `UiPagesController.adminIdentityProviders(...)` resuelve `IdentityClient` vía `clientContextResolver.resolveClient(clientId)` y arma dos valores concretos (Google/Facebook) con `SocialRegistrationId.of(identityClient.getId(), provider)` — nuevo método `of()`/`toString()` agregado a `SocialRegistrationId` (ticket 037) como inverso simétrico de `parse()`, mismo formateador que debe reutilizar el ticket 039.
- `admin-identity-providers.html`: dos atributos (`oauth2RedirectUriGoogle`/`oauth2RedirectUriFacebook`), texto corregido.
- `docs/definiciones/login-social-real.md` (Decisión 1 / OQ-1) corregido explícitamente, no en silencio.
- `UiPagesControllerTest` actualizado: confirma ambos valores resueltos con UUID real y confirma explícitamente que `{registrationId}` ya no aparece sin resolver.
- 308/308 tests en verde. Quality Gate de SonarQube verificado en local antes del PR (misma rutina ya establecida en tickets anteriores): OK, 0 violaciones nuevas (1 corregida: `java:S1075` en el nuevo prefijo de ruta, `@SuppressWarnings` documentado, mismo criterio que ya se usó en el 040 original).
- **Verificación en vivo parcial, no completa — dicho explícito, no asumido:** el valor mostrado para Acme se registró en la consola real de Google Cloud (ticket 043) y coincide con lo que `TenantAwareClientRegistrationRepository` espera. **No se completó un login social de punta a punta** porque eso requiere los tickets 038 (canje por tokens) y 039 (UI/páginas reales) — todavía dan 404 (rutas placeholder documentadas en el ticket 037). Ese último tramo de verificación queda para cuando 038/039 estén mergeados, no se cierra en silencio como si ya hubiera pasado.
- Detalle completo en `docs/ARQUITECTURA.md` (sección "Ticket 044").
