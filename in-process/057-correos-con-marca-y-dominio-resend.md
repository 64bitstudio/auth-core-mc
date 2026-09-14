# 057 — Correos con marca por tenant + dominio real de Resend

## Objetivo
Dos pendientes relacionados, resueltos juntos porque el segundo no tenía
sentido probarlo de verdad sin el primero: (1) los correos de
verificación/reset/cambio de email se mandaban como texto plano sin
ningún diseño (`<p>Click to verify...</p>`), y (2) `mail.auth.64bitstudio.com`
seguía sin verificarse en Resend desde el ticket 056 — dev usaba el
remitente sandbox `onboarding@resend.dev` como parche temporal.

## Decisión de Marco
Correos con marca **por tenant**, reusando `tenant.app_name`/
`tenant.primary_color` (ya existían para las páginas hospedadas
`/ui/**` — cero columnas nuevas) en vez de un diseño genérico único de
64Bit Studio para todos.

## Alcance
- **Sí incluye:**
  - `BrandedEmailTemplate` (nuevo, `notification`): HTML con layout de
    tabla + estilos inline (los `<style>` no sobreviven en varios
    clientes de correo reales, Outlook desktop el peor) — header con el
    color del tenant, botón CTA, link de respaldo en texto plano, footer.
    Color inválido/ausente cae a un fallback seguro; el nombre del tenant
    se escapa antes de insertarse en el HTML (dato administrado, no de un
    atacante, pero igual no debe entrar sin validar a un atributo HTML).
  - `EmailVerificationService`, `PasswordResetService`,
    `EmailChangeService`: usan la plantilla nueva en vez del `<p>` plano;
    el asunto también incluye `tenant.app_name`.
  - Dominio `mail.auth.64bitstudio.com` verificado en Resend (registros
    DKIM/SPF agregados en Cloudflare vía su API, con confirmación previa
    de Marco sobre los 3 registros exactos).
  - `RESEND_FROM_ADDRESS` de dev actualizado a
    `noreply@mail.auth.64bitstudio.com` (deja de usar el sandbox).
- **No incluye:** un editor de plantillas por tenant (esto es un
  template fijo parametrizado por 2 campos ya existentes, no un sistema
  de plantillas configurable); tocar SMS (Twilio, texto plano, sin
  aplicar aquí); actualizar `RESEND_FROM_ADDRESS` de qa/prod (queda
  pendiente, no verificado en este ticket si ya estaban configurados).

## Criterios de aceptación (TDD)
- `BrandedEmailTemplateTest`: incluye nombre/color del tenant, cae a un
  color de fallback seguro si `primary_color` no es un hex válido, y
  escapa HTML en `app_name`.
- Los tests existentes de los tres servicios (que ya verificaban
  `contains("the-token")` en el cuerpo del correo) siguen en verde sin
  modificarse — la plantilla nueva sigue incluyendo el link real.
- Verificación en vivo: un correo real disparado desde dev llega con el
  diseño nuevo (marca de galgoth-studio: nombre + verde menta) y desde el
  dominio verificado, no desde el sandbox de Resend.

## Hecho
