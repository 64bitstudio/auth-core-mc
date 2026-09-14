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
