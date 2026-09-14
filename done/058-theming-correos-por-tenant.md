# 058 — Theming rico de correo por tenant

## Objetivo
Marco compartió un diseño completo (arte propio: portal hero, fondo
lateral, header/footer con redes sociales) para el correo de
verificación de galgoth-studio — mucho más específico que el theming
genérico (`app_name`/`primary_color`) del ticket 057.

## Decisión de Marco
auth-core-mc es el servicio de identidad **compartido** (lo usan
galgoth-studio, mail-core-mc, etc.) — este diseño es específico de un
tenant. En vez de hardcodear el diseño de Galgoth Studio dentro del
servicio compartido, se construye un **sistema de theming por tenant**:
campos nuevos opcionales en `Tenant` (imágenes + textos cortos + links de
redes) que alimentan un layout FIJO que auth-core-mc sigue controlando —
nunca HTML arbitrario del tenant, solo imágenes/texto corto en espacios
fijos. Un tenant sin estos campos configurados sigue usando el diseño
simple del ticket 057.

**Alcance de esta primera pasada** ("comencemos con ese", palabras de
Marco): solo el correo de **verificación de cuenta**. Password-reset y
change-email quedan con el copy/heading actualizado a la firma nueva del
método (necesario porque comparten `BrandedEmailTemplate.build(...)`),
pero su copy sigue siendo genérico — el mismo layout rico se activa
automáticamente para ellos también en cuanto un tenant tenga el theme
configurado, sin más cambios de código.

## Hallazgo/decisión de copy (transparente, no asumido en silencio)
El mockup trae copy específico de producto ("crear increíbles mobs de
Minecraft"). Mantener esa especificidad en el servicio compartido
reintroduciría exactamente el acoplamiento que esta decisión evitó. El
copy que quedó en `EmailVerificationService`/`PasswordResetService`/
`EmailChangeService` es genérico (aplica a cualquier tenant), en español,
con el nombre del tenant en negritas vía el placeholder `{appName}` que
`BrandedEmailTemplate` sustituye de forma segura (nunca HTML arbitrario).
Se cambió también el idioma del copy existente (antes en inglés) a
español — Marco puede pedir textos en inglés para un tenant futuro si
hace falta, pero hoy todo el trabajo de este dominio es en español.

## Alcance
- **Sí incluye:**
  - Migración `V11`: 10 columnas nuevas nullable en `tenant`
    (`email_logo_url`, `email_hero_image_url`, `email_side_image_url`,
    `email_header_subtitle`, `email_header_tagline`,
    `email_footer_tagline`, `email_discord_url`, `email_youtube_url`,
    `email_twitter_url`, `email_github_url`).
  - `EmailTheme` (record) + `Tenant.getEmailTheme()`/`updateEmailTheme(...)`
    — `heroImageUrl` es la señal de "theme listo" (sin ella, se trata como
    no configurado aunque otros campos sí estén).
  - `BrandedEmailTemplate` con dos niveles: rico (header con logo+tagline,
    hero image, heading de dos tonos, botón, caja de "copiar link",
    vencimiento real del TTL, sección "¿no fuiste tú?", footer con redes
    sociales como texto — sin iconos reales todavía, ver Pendiente) y
    simple (sin cambios, ticket 057).
  - Assets (`galgoth-studio` PR #113): logo/hero/fondo lateral servidos
    desde `frontend/public/email/` de galgoth-studio (mismo mecanismo que
    favicon.svg/icons.svg) — auth-core-mc los referencia por URL pública,
    no los aloja.
  - El theme de galgoth-studio se pobló directo en la base de cada
    ambiente (mismo criterio operativo que la alta original del tenant en
    el ticket 052) — no hay endpoint admin para esto todavía (ver
    Pendiente).
- **No incluye:** endpoint admin para editar el theme (hoy es SQL
  directo, igual que el tenant/client originales); iconos reales para
  redes sociales (texto por ahora); copy específico por tenant más allá
  del nombre en negritas; aplicar el diseño rico también a
  password-reset/change-email con copy propio (funciona automáticamente
  con el copy genérico ya escrito, pero no se diseñó un mockup específico
  para esos dos todavía).

## Criterios de aceptación (TDD)
- `BrandedEmailTemplateTest`: sin theme usa el diseño simple; con theme
  usa el rico (hero, header, footer, redes sociales presentes solo si su
  URL está configurada); un theme sin `heroImageUrl` se trata como no
  configurado; el vencimiento mostrado es el TTL real del tenant, nunca
  un valor fijo.
- Suite completa del proyecto en verde.
- Verificación en vivo: un correo de verificación real disparado desde
  dev para galgoth-studio llega con el diseño rico completo (imágenes
  cargando de verdad, no rotas).

## Hecho
- Migración `V11` aplicada (10 columnas nullable en `tenant`), `EmailTheme`
  (record) + `Tenant.getEmailTheme()`/`updateEmailTheme(...)` implementados
  — `heroImageUrl` como señal de "theme listo".
- `BrandedEmailTemplate` reescrito con `build(tenant, headingPlain,
  headingAccent, bodyText, ctaLabel, ctaUrl, expiryHours)`: diseño rico
  (`buildRich`) cuando el tenant tiene theme, simple (`buildSimple`, sin
  cambios del ticket 057) cuando no. `EmailVerificationService`,
  `PasswordResetService` y `EmailChangeService` migrados a la firma nueva
  con copy genérico en español y `{appName}` en negritas vía el
  tenant que corresponda.
- Suite de `BrandedEmailTemplateTest` en verde, incluyendo los casos
  nuevos: sin theme usa el simple, theme sin `heroImageUrl` se trata como
  no configurado, redes sociales solo aparecen si su URL está configurada,
  vencimiento mostrado = TTL real del tenant (no fijo).
- Imágenes (`galgoth-studio` PR #113): `logo.png`, `hero-verify.jpg`,
  `side-bg.jpg` publicadas en `frontend/public/email/` y verificadas
  accesibles en `https://studio-dev.galgoth.64bitstudio.com/email/*`
  (200 OK, `curl -I` a las 3) antes de disparar cualquier correo real.
- Theme de galgoth-studio poblado en la base de **dev** (mismo criterio
  operativo que el alta del tenant en el ticket 052 — SQL directo, sin
  endpoint admin todavía): `email_logo_url`, `email_hero_image_url`,
  `email_side_image_url`, `email_header_subtitle` ("CREA · EDITA · DA
  VIDA"), `email_header_tagline` ("Tu mundo. / Tus criaturas. / Sin
  límites."), `email_footer_tagline` ("Edición. Creatividad. Mundos
  infinitos."). Las URLs de redes sociales (Discord/YouTube/X/GitHub)
  quedaron en `NULL` a petición explícita de Marco — todavía no existen
  esas cuentas/enlaces listos para publicar; el template ya maneja esto
  sin romperse (el footer simplemente omite los links ausentes). Pendiente
  poblarlas con un `UPDATE` cuando Marco las tenga.
- **Verificación en vivo**: `POST /api/v1/verify-email/request` disparado
  contra dev real (`X-Client-Id: galgoth-studio`, la cuenta de prueba
  `marcocortes1234.mc@gmail.com`) → `202 Accepted`. Marco confirmó
  visualmente que el correo recibido coincide con su mockup "Confirma tu
  correo" (header, hero, footer, botón, caja de copiar-link, todo
  cargando correctamente).
- Solo dev quedó poblado con el theme — qa/prod de galgoth-studio siguen
  sin estos campos (usarán el diseño simple del ticket 057 hasta que se
  pida promoverlos), consistente con que esta ronda de trabajo no
  incluyó promoción a esos ambientes.
