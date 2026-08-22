# 036 — `TenantAwareClientRegistrationRepository` + wiring en `SecurityConfig`

## Objetivo
Nace de `docs/definiciones/login-social-real.md` (Diseño técnico, decisiones 1, 2 y 5). Construye el componente central que resuelve, por request y sin cache, las credenciales OAuth2 del tenant correcto a partir del `registrationId` — el mecanismo que preserva el tenant a través del redirect/callback sin depender de sesión de servidor.

**Depende de:** ticket 035 (no depende de la tabla `external_identity` en sí, pero es parte de la misma cadena de wiring que sí la usa después). Es prerequisito de 037, 038, 040.

## Criterios de aceptación (TDD)
- `TenantAwareClientRegistrationRepository implements ClientRegistrationRepository`, mismo patrón que `TenantAwareRegisteredClientRepository` ya existente: `findByRegistrationId("{identityClient.id}::{provider}")` parsea el UUID de `IdentityClient` + el proveedor, resuelve `Tenant` → `TenantIdentityProvider` (valida `enabled`), descifra `client_secret` vía `TenantSecretEncryptor`, y construye el `ClientRegistration` partiendo de `CommonOAuth2Provider.GOOGLE`/`.FACEBOOK`, sobreescribiendo `clientId`/`clientSecret`/`redirectUri`. Sin cache — se reconstruye en cada lookup.
- `redirectUri` es el mismo para todos los tenants (confirmado en la definición, OQ-1) — no varía por tenant, solo el `registrationId` en la ruta.
- **Requisito de seguridad explícito:** `findByRegistrationId` devuelve `null` de forma idéntica tanto si el UUID de `IdentityClient` no existe como si existe pero el proveedor no está `enabled` en `TenantIdentityProvider` — nunca un mensaje o comportamiento distinto entre ambos casos (evita enumeración de tenants/proveedores configurados). Verificado con un test explícito para cada caso.
- `SecurityConfig`: agrega `permitAll` para `/oauth2/authorization/**` y `/login/oauth2/code/**` (mismo bloque que ya cubre `/ui/**`), y `.oauth2Login(...)` apuntando a este repositorio nuevo. **`AuthorizationServerConfig` no se toca** — su `securityMatcher` ya excluye estructuralmente estas rutas (verificar con un test que las rutas de Authorization Code + PKCE existentes siguen funcionando sin cambios).
- Tests: resolución exitosa con un `TenantIdentityProvider` habilitado real (fixture); `null` para UUID inexistente; `null` para proveedor deshabilitado; ningún test de `/api/v1/login` ni de `/oauth2/authorize` (grant directo, ticket 007) cambia su resultado.

## Hecho
- `TenantAwareClientRegistrationRepository` implementado exactamente según el diseño: parsea `registrationId`, resuelve `IdentityClient → Tenant → TenantIdentityProvider`, descifra el secret vía `TenantSecretEncryptor`, construye `ClientRegistration` desde `CommonOAuth2Provider.GOOGLE`/`.FACEBOOK`. Sin cache.
- `redirectUri` reseteado explícitamente al placeholder de Spring (`{baseUrl}/login/oauth2/code/{registrationId}`) en vez de confiar en el default implícito — mismo valor para todos los tenants (OQ-1), documentado en el Javadoc del porqué.
- **Requisito de seguridad verificado con test dedicado:** `anUnknownUuidAndADisabledProviderAreIndistinguishable` corre ambos casos (UUID inexistente / proveedor deshabilitado) y compara sus resultados con `isEqualTo` — no solo "ambos dan null" por separado, sino indistinguibles entre sí.
- Dependencia nueva `spring-boot-starter-oauth2-client` en `build.gradle` — confirmado que NO era transitiva de `spring-boot-starter-security-oauth2-authorization-server` (son dos módulos de Spring Security distintos: cliente OAuth2 vs. servidor de autorización).
- `SecurityConfig`: `permitAll` para `/oauth2/authorization/**`/`/login/oauth2/code/**` + `.oauth2Login(...)`. Sin `successHandler`/`failureHandler` propios todavía (ticket 037, fuera de alcance aquí).
- **`AuthorizationServerConfig` confirmado intacto, no solo por no editarlo:** su `securityMatcher` (`@Order(1)`, `getEndpointsMatcher()` de `OAuth2AuthorizationServerConfigurer`) solo cubre `/oauth2/authorize`, `/oauth2/token`, `/oauth2/jwks`, etc. — las rutas nuevas (`/oauth2/authorization/**`, prefijo distinto; `/login/oauth2/code/**`) son del lado *cliente* OAuth2, caen en la chain de `SecurityConfig`. Verificado no solo leyendo el código: `TenantAwareRegisteredClientRepositoryTest` (Authorization Code+PKCE) y `AuthControllerTest` (grant directo) siguen en el mismo conteo/resultado que antes del ticket.
- 9 tests `@WebMvcTest` existentes necesitaron `@MockitoBean private ClientRegistrationRepository` nuevo (mismo patrón ya establecido con `JwtDecoder` desde el ticket 012 — el bean deja de ser opcional para construir la cadena de filtros en esa slice).
- `docs/API.md`: sección nueva marcada explícitamente "en construcción" (sin successHandler, sin creación de app_user, sin exchange endpoint — llega en 037/038, no se finge completo).
- 272/272 tests en verde (260 preexistentes + 12 nuevos), confirmado leyendo los XML de resultados, no solo el log de consola.
- Detalle completo en `docs/ARQUITECTURA.md` (sección "Ticket 036").
