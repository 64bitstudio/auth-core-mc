# 063 — Vincular una cuenta social nueva desde el perfil

## Objetivo
Ver `docs/definiciones/perfil-de-usuario.md` de galgoth-studio (VoBo de
Marco recibido) — HU-7/HU-8. `external_identity` ya existe (registra qué
proveedores están vinculados a cada usuario, poblado hoy solo por el
login social). Falta: (a) exponer ese estado por lectura, y (b) el flujo
para vincular un proveedor nuevo a una sesión YA autenticada, sin pasar
por "iniciar sesión como quien sea que resuelva ese perfil" — ver Diseño
técnico §4 del documento (el `state` de la request OAuth lleva la
intención de vínculo).

**Depende de:** nada nuevo — reutiliza el mecanismo OAuth/`RedisTokenStore`
ya construido para el login social (tickets 036-038).

## Alcance
- **Sí incluye:**
  - `GET /api/v1/account/connected-providers` (autenticado): lista
    Google/Facebook con su estado (`vinculada`/`no vinculada`), leyendo
    `external_identity` real del usuario.
  - `POST /api/v1/account/link-provider/{provider}` (autenticado):
    emite un token de intención de corta vida (`RedisTokenStore`,
    `{userId, provider}`) y devuelve la URL de autorización OAuth con
    ese token embebido en el `state` (junto al `state` anti-CSRF que
    Spring Security ya gestiona).
  - `SocialLoginSuccessHandler`: si el `state` trae una intención de
    vínculo válida, no hace `SocialLoginUserResolver.resolve(...)` —
    llama a `ExternalIdentityLinkService.link(user, provider, profile)`
    (nuevo, extrae `linkIdentity` de `SocialLoginUserResolver` a un
    lugar compartido) y redirige a `/usuario?linked={provider}` en vez
    de al flujo de intercambio de tokens de login (la sesión ya era
    válida antes de salir a Google/Facebook, no hace falta mintear
    nada).
  - `ExternalIdentityLinkService.link(...)`: si el `(tenant, provider,
    providerUserId)` ya pertenece a OTRO usuario, falla explícito
    (`PROVIDER_ALREADY_LINKED_TO_ANOTHER_USER` o similar) y redirige con
    ese error; si ya pertenece al MISMO usuario, no-op (idempotente); si
    no existe, inserta la fila.
- **No incluye:** desvincular una cuenta ya conectada (no lo pidió el
  mockup — solo "Vinculada"/"Conectar"), Apple como proveedor (fuera de
  alcance del documento).

## Criterios de aceptación (TDD)
- `GET /connected-providers` refleja el estado real de
  `external_identity` para el usuario autenticado.
- Vincular un proveedor nuevo (perfil real de Google/Facebook en test,
  sin login previo con ese proveedor) → queda vinculado AL USUARIO QUE
  INICIÓ el flujo, nunca crea un usuario nuevo ni cambia de sesión.
- Intentar vincular un proveedor ya vinculado a OTRO usuario del mismo
  tenant → rechazado explícito, sin desvincularlo del original.
- Repetir la vinculación de un proveedor ya vinculado al MISMO usuario →
  no falla (idempotente).
- Suite completa en verde.
- Verificación en vivo contra DEV: vincular una cuenta de Google real a
  un usuario de prueba ya logueado, confirmar la fila en
  `external_identity` y que la sesión original sigue intacta.

## Hecho
- **Desviación deliberada del documento de definición, documentada aquí
  en vez de aplicada en silencio**: el diseño original proponía llevar la
  intención de vínculo en el `state` de la request OAuth (vía
  `RedisTokenStore`). Al implementar se encontró un mecanismo más simple
  y ya existente: Spring ya crea una sesión HTTP para correlacionar el
  redirect de `oauth2Login` con su callback (ver el propio Javadoc de
  `SecurityConfig`, "no se usa como auth continua, solo como
  correlación"). `LinkIntentSession` reutiliza esa MISMA sesión (un
  atributo de un solo uso) en vez de tocar el `state` de OAuth2 (que es
  responsabilidad interna de Spring Security, un CSRF token, no un lugar
  para meter datos de negocio) o construir un resolver de autorización
  OAuth2 a medida. Mismo resultado funcional, superficie de cambio mucho
  más chica.
- `GET /api/v1/account/connected-providers`, `POST
  /api/v1/account/link-provider/{provider}` (`AccountLinkProviderController`)
  — `client_id` se lee del claim `aud` del JWT, no de un header aparte.
  Apple explícitamente rechazado (`UnsupportedProviderException`, mismo
  patrón que `TenantIdentityProviderService`).
- `ExternalIdentityLinkService`: `link(...)` (conflicto real vs. no-op
  idempotente vs. inserción nueva) + `listConnected(...)`.
- `SocialLoginSuccessHandler` bifurca al inicio: con intención de vínculo
  en la sesión, nunca llama a `SocialLoginUserResolver.resolve(...)` ni
  emite tokens — llama a `ExternalIdentityLinkService.link(...)` y
  redirige a `{origen propio del cliente}/usuario` con
  `linked=`/`link_error=`. La intención se consume (borra) al leerla, así
  que un login normal posterior en la misma sesión de navegador nunca la
  hereda (probado explícitamente).
- `docs/API.md` actualizado.
- Tests: 4 casos nuevos en `SocialLoginSuccessHandlerTest` (vínculo
  exitoso sin tocar el resolver de login, proveedor ya vinculado a otro
  usuario rechazado, consumo de un solo uso, sin email redirige al
  perfil no al login) — mismo patrón que los tests de login social ya
  existentes (simulan el `Authentication` que Spring produce tras el
  consentimiento real, no hay forma de automatizar el consentimiento de
  un proveedor externo en un test). `AccountLinkProviderControllerTest`
  (real JWT/DB) para las 2 piezas puramente REST. Suite completa en
  verde.
- **Verificación en vivo contra DEV**: cuenta de prueba real —
  `GET /connected-providers` recién creada → ambos `false`;
  `POST /link-provider/google` → `200` con una URL real de
  `/oauth2/authorization/{registrationId}`; esa URL, seguida de verdad,
  responde `302` a `https://accounts.google.com/o/oauth2/v2/auth` con
  `client_id`/PKCE/`state` reales (confirmado con el `client_id` de
  Google real de dev); `link-provider/apple` → `400
  unsupported_provider`. Completar el consentimiento real de Google
  requiere un humano en un navegador (no se puede automatizar ni debe
  automatizarse con credenciales) — el mecanismo hasta ese punto (sesión,
  redirect, rechazo de Apple) está verificado en vivo; el vínculo en sí
  tras el consentimiento está cubierto por los 4 tests de
  `SocialLoginSuccessHandlerTest` que simulan el `Authentication` real
  que Spring produce (mismo patrón ya establecido por los tests de login
  social del ticket 037). Datos de prueba limpiados.
