# 002 — Registro y login con email/teléfono + contraseña

## Objetivo
Endpoints REST para registro (email o teléfono + password) y login directo (first-party, ver ticket 007 para el flujo redirect). Hash Argon2id. Validaciones de fuerza de contraseña y de formato de teléfono/email.

## Criterios de aceptación (TDD)
- No permitir registro sin al menos un identificador (email o teléfono).
- No permitir duplicados dentro del mismo tenant.
- Rate limiting de intentos de login (Redis) para mitigar fuerza bruta.
