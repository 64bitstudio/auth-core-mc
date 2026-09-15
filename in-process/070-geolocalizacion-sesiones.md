# 070 — Geolocalización de "Sesiones activas"

## Objetivo
Ticket retroactivo. Al alinear "Mi Perfil" (galgoth-studio#079) con su mockup original, "Sesiones activas" mostraba ciudad por dispositivo (ej. "Ciudad de México, México") — dato que el ticket 062 decidió explícitamente NO capturar en su momento ("Sin geolocalización de IP, decisión de Marco", ver `V13__refresh_token_sessions.sql`). Marco decidió ahora sí construirlo, con VoBo explícito sobre cada tradeoff real:

- **Fuente de datos**: base de datos local GeoLite2-City de MaxMind (no un servicio externo en vivo) — ninguna IP de usuario se manda a un tercero por request, a costa de menos precisión de ciudad que un servicio en vivo.
- **Descarga/actualización**: el sidecar oficial `geoipupdate` de MaxMind (nunca reimplementado a mano — protocolo real de descarga con auth/reintentos/verificación de integridad).

## Criterios de aceptación (TDD)
- `refresh_token` gana `client_ip` (nullable, capturado en login directo/social/2FA-verify — los mismos 3 choke points del `userAgent` del ticket 062).
- `GeoIpService` resuelve ciudad/país en caliente desde `client_ip` -- nunca guardado, así que una actualización de la base de datos de MaxMind aplica retroactivamente sin migrar nada.
- Degradación explícita: sin base de datos disponible (sidecar sin credenciales, o primera vez) o IP no resoluble (privada/reservada/desconocida), `city`/`country` son `null` -- "Sesiones activas" nunca se rompe por esto.
- `server.forward-headers-strategy` pasa de `framework` a `native` (necesario para que `request.getRemoteAddr()` refleje la IP real del cliente detrás de Traefik) -- verificado que el fix del ticket 068 (esquema `https://` del `redirect_uri` de OAuth2) sigue funcionando con `native`.
- Suite completa en verde, incluyendo un test end-to-end real contra la base de prueba oficial de MaxMind (sin red, sin license key) que prueba login → sesión con ciudad/país reales.
- Verificado en vivo contra dev con una license key real de MaxMind.

## Hecho
