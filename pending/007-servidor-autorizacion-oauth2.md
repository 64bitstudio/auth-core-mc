# 007 — Servidor de autorización OAuth2 (Spring Authorization Server)

## Objetivo
Exponer Authorization Code + PKCE (estándar, para integraciones de terceros y flujo con UI) y un endpoint de login directo protegido para clientes first-party (email/password → token, sin redirect). Tokens JWT con tiempos de expiración (access/refresh/sesión) parametrizables por tenant y por cliente OAuth2.

## Criterios de aceptación (TDD)
- Un cliente third-party solo puede usar Authorization Code+PKCE (el grant directo se rechaza si el cliente no está marcado como first-party).
- Los tiempos de expiración son de configuración, no de código (parametrizables sin redeploy).
- Revocación de refresh token inmediata vía Redis.
