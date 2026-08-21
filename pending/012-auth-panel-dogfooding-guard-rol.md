# 012 — Auth del panel (dogfooding) + guard de rol

## Objetivo
El panel de administración se autentica usando el propio auth-core-mc (dogfooding), y cada request a los endpoints de administración pasa por un guard que verifica el rol del ticket 011. Nace de `docs/definiciones/panel-administracion-clientes.md` (HU-3, diseño técnico, diagrama de secuencia "acceso al panel con control de rol").

**Depende de:** ticket 011 (RBAC).

## Criterios de aceptación (TDD)
- Un usuario del panel inicia sesión vía el flujo de login ya existente de auth-core-mc; el JWT resultante incluye claim de rol y tenant_id.
- El guard de rol intercepta cada request a rutas de administración y aplica la regla: autorizado si `rol=platform_admin` O (`rol=tenant_admin` Y `tenant_id` coincide).
- Requests sin JWT válido, o con rol insuficiente, responden 401/403 según corresponda — no 500 ni comportamiento ambiguo.
- Documentado en `docs/ARQUITECTURA.md` el riesgo de dependencia circular (si auth-core-mc está degradado, el panel también lo está) — mitigado por el ticket 018 (break-glass).

## Hecho
