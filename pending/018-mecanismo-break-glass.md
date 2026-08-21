# 018 — Mecanismo de break-glass para acceso de emergencia

## Objetivo
Vía de acceso administrativo independiente del flujo normal de login (dogfooding, ticket 012), para cuando auth-core-mc esté degradado o caído y el equipo necesite diagnosticar/intervenir. Nace de `docs/definiciones/panel-administracion-clientes.md` (Riesgos y decisiones — dependencia circular).

## Criterios de aceptación (TDD)
- Endpoint de emergencia que NO depende de `AuthController`/OAuth2 — sigue funcionando si el flujo normal de auth tiene un incidente.
- Gateado por un secreto pre-compartido rotable (no hardcodeado — vía variable de entorno/gestor de secretos).
- Cada uso queda registrado con detalle (quién, cuándo, qué se hizo) — auditoría fuerte, dado que es una puerta de acceso de alto privilegio.
- Segundo factor / restricción adicional (allowlist de IP, TOTP, etc.) — detalle final a confirmar en el diseño de este ticket específico (pregunta abierta heredada de la definición).

## Hecho
