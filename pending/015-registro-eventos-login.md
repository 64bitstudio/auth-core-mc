# 015 — Registro de eventos de login (login_event)

## Objetivo
Instrumentar el flujo de autenticación existente para registrar cada intento de login (éxito o fallo) — base de datos para las métricas del ticket 016. Nace de `docs/definiciones/panel-administracion-clientes.md` (diseño técnico, HU-4).

## Criterios de aceptación (TDD)
- Nueva tabla `login_event` (tenant_id, user_id nullable, provider, outcome SUCCESS/FAILURE, latency_ms, occurred_at) — sin particionamiento (volumen bajo/moderado esperado, decisión tomada en la definición).
- Cada intento de autenticación real (password, OAuth social) inserta un `login_event`, sin afectar de forma perceptible la latencia del login en sí.
- Tests que verifiquen que se registra tanto el éxito como el fallo, con el proveedor correcto.

## Hecho
