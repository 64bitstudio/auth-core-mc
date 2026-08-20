# 008 — Multi-tenencia y clonado a instancia dedicada

## Objetivo
Modelo de datos y despliegue que permita: (a) operar como servicio multi-tenant compartido (como Auth0/Keycloak), y (b) "clonarse" 1:1 como instancia 100% aislada (su propia BD/contenedor) para un proyecto que lo amerite.

## Criterios de aceptación (TDD)
- Todo dato de negocio está particionado por `tenant_id` (ninguna consulta cruza tenants por accidente — tests que lo verifiquen).
- Script/proceso documentado para levantar una instancia dedicada desde cero con un solo tenant "semilla".
