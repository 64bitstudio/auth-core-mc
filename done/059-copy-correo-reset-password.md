# 059 — Ajustar copy del correo de reset de contraseña al mockup

## Objetivo
Marco compartió un mockup específico ("Restablece tu contraseña") para el
correo de recuperación de contraseña de galgoth-studio. A diferencia del
ticket 058 (que introdujo el sistema de theming rico y lo aplicó al
correo de verificación), este mockup usa un hero/header/footer distintos
en apariencia al de verificación — había que decidir si eso implica
theming por tipo de correo (cambio de modelo de datos) o si el theme ya
sembrado por tenant alcanza.

## Decisión de Marco (vía AskUserQuestion)
- **Mismo header/footer para los 3 tipos de correo** — no se crea theming
  por tipo de correo, se mantiene un solo `EmailTheme` por tenant (el
  mockup era ilustrativo de layout, no de copy exacto de esos textos).
- **Mismo hero image que verificación** — no hay imagen nueva que alojar,
  sigue siendo un solo `heroImageUrl` por tenant.
- **Redes sociales siguen en NULL** — las cuentas de Discord/YouTube/X/
  GitHub siguen sin existir; los iconos del mockup son aspiracionales.
  Iconos reales (en vez de texto) siguen fuera de alcance (ya lo estaba
  desde el 058).

Con estas tres decisiones, **no hace falta ningún cambio de arquitectura
ni de modelo de datos** — el diseño rico ya sembrado en el ticket 058 se
activa automáticamente para el correo de reset (mismo mecanismo:
`BrandedEmailTemplate.build(...)` con el theme del tenant). El único
ajuste real es afinar el copy del cuerpo y el botón en
`PasswordResetService` para que coincidan con el mockup.

## Alcance
- **Sí incluye:** actualizar `bodyText` y `ctaLabel` en
  `PasswordResetService.requestReset` para calzar con el mockup
  ("Si fuiste tú, haz clic en el botón para crear una nueva contraseña." /
  "Restablecer mi contraseña").
- **No incluye:** theming por tipo de correo, hero nuevo, iconos reales de
  redes sociales, URLs reales de redes sociales (todo eso quedó
  explícitamente descartado en la ronda de preguntas de este ticket).

## Criterios de aceptación (TDD)
- Suite completa del backend en verde (`./gradlew test`).
- Verificación en vivo: un correo de reset real disparado desde dev para
  galgoth-studio usa el diseño rico (mismo hero/header/footer que
  verificación) con el copy nuevo, confirmado visualmente por Marco
  contra su mockup.

## Hecho
- `PasswordResetService.requestReset` actualizado: `bodyText` → "Recibimos
  una solicitud para restablecer la contraseña de tu cuenta en {appName}.
  Si fuiste tú, haz clic en el botón para crear una nueva contraseña.",
  `ctaLabel` → "Restablecer mi contraseña" (antes "Restablecer
  contraseña"). Sin cambios de arquitectura ni de modelo de datos — el
  diseño rico del ticket 058 ya se activaba automáticamente aquí (mismo
  `EmailTheme` por tenant, mismo hero/header/footer que verificación),
  confirmado explícitamente por Marco vía AskUserQuestion antes de tocar
  código.
- Suite completa del backend en verde (`./gradlew test`).
- **Verificación en vivo**: `POST /api/v1/password-reset/request`
  disparado contra dev real (`X-Client-Id: galgoth-studio`, identifier
  `marcocortes1234.mc@gmail.com`) → `202 Accepted`. Marco confirmó
  ("todo correcto") que el correo recibido coincide con su mockup
  "Restablece tu contraseña".
