# 068 — OAuth2: `redirect_uri` usaba el esquema interno (`http://`) detrás de Traefik

## Objetivo
Ticket retroactivo. Tercer hallazgo real durante la verificación en vivo de galgoth-studio#093 (Pantalla "Usuario", "Cuentas conectadas" → "Conectar" con una cuenta de Google real) — y la primera vez que CUALQUIER flujo `oauth2Login` de este proyecto se probó de verdad en un navegador contra un ambiente desplegado (todos los tests existentes de login social pasan por MockMvc directo, sin Traefik de por medio).

Traefik termina TLS y reenvía a este contenedor por HTTP plano, mandando `X-Forwarded-Proto: https` (estándar). Spring Boot no confía en ese header por defecto (`server.forward-headers-strategy=none`), así que `OAuth2AuthorizationRequestRedirectFilter` construía el `redirect_uri` con el esquema de la conexión INTERNA (`http://auth-dev.64bitstudio.com/login/oauth2/code/...`) en vez del externo real (`https://`). Google lo rechazó con `redirect_uri_mismatch` (error real, verificado en pantalla) porque no coincidía con el `redirect_uri` registrado en Google Cloud Console.

## Criterios de aceptación (TDD)
- Sin `server.forward-headers-strategy=framework`, el `redirect_uri` calculado usa el esquema interno (`http://`) — test que documenta el bug real (caracterización).
- Con la propiedad activa, el `redirect_uri` usa el esquema externo real (`https://`) que manda `X-Forwarded-Proto`.
- Solo activo en el perfil `deploy` (nunca local/tests) — `http://localhost:8080` es un `redirect_uri` real registrado en Google Cloud Console para pruebas locales (ver `docs/ARQUITECTURA.md`), y forzar HTTPS ahí lo rompería.
- Verificado en vivo contra dev: "Conectar" con una cuenta de Google real ya no falla con `redirect_uri_mismatch`.

## Hecho
Una línea en `application-deploy.properties`: `server.servlet.session.cookie.secure`/`.same-site` (el 067) más `server.forward-headers-strategy=framework` — mecanismo estándar de Spring Boot para apps detrás de un reverse proxy que termina TLS.

Tests nuevos (ambos verdes localmente, mismo request exacto, única diferencia la propiedad activa — no dependen de Vault real, `TenantSecretEncryptor` mockeado):
- `ForwardedHeadersOAuth2RedirectIntegrationTest` — caracteriza el bug (sin la propiedad, esquema `http://`).
- `ForwardedHeadersOAuth2RedirectFixedIntegrationTest` — prueba el fix (con la propiedad, esquema `https://` real).

CI de Jenkins verde (ver PR). Verificado en vivo contra dev después del deploy, como parte de la verificación en vivo de galgoth-studio#093: "Conectar" con una cuenta de Google real ya no cae en `redirect_uri_mismatch` — completa el consentimiento de Google.

No rompe compatibilidad — puramente aditivo, y acotado al perfil `deploy` para no afectar el flujo ya funcional de pruebas locales de OAuth2 contra `http://localhost:8080`.
