# 008 — Multi-tenencia y clonado a instancia dedicada

## Objetivo
Modelo de datos y despliegue que permita: (a) operar como servicio multi-tenant compartido (como Auth0/Keycloak), y (b) "clonarse" 1:1 como instancia 100% aislada (su propia BD/contenedor) para un proyecto que lo amerite.

## Criterios de aceptación (TDD)
- Todo dato de negocio está particionado por `tenant_id` (ninguna consulta cruza tenants por accidente — tests que lo verifiquen).
- Script/proceso documentado para levantar una instancia dedicada desde cero con un solo tenant "semilla".

## Hecho
- `TenantIsolationTest` (nuevo, paquete `repository`): prueba holísticamente que `findByTenantAndEmail`, `findByTenantAndPhone` y `findByTenantAndProvider` nunca cruzan tenants, incluso con el mismo email/teléfono/proveedor duplicado entre dos tenants. El aislamiento en sí ya existía desde el ticket `001` — este ticket lo demuestra con una prueba dedicada en vez de confiar en cobertura incidental.
- Documentados (con test explícito, no solo en prosa) los dos lookups deliberadamente NO tenant-scoped: `IdentityClientRepository.findByClientId` (es el mecanismo que resuelve el tenant, no puede depender de conocerlo antes) y `RefreshTokenRepository.findByTokenHash` (hash de alta entropía, filtrar por tenant no añadiría aislamiento real). Se prueba que la fila devuelta siempre pertenece al tenant correcto.
- `backend/scripts/export-tenant.sh` / `import-tenant.sh`: exporta/importa un tenant completo (tenant, app_user, tenant_identity_provider, identity_client, refresh_token) como SQL corriente (bloques `COPY ... FROM stdin`), en el orden correcto de dependencias FK. No requiere más que `psql` y variables de conexión libpq — agnóstico de proveedor cloud.
- Verificado en vivo, extremo a extremo (no solo con tests): exportado el tenant real `Acme` (con su usuario, cliente OAuth2 y un refresh token ya revocado) desde el Postgres del ticket `007`, importado en un segundo contenedor Postgres nuevo con el esquema `V1`+`V2` recién aplicado, y confirmados los datos íntegros (incluyendo el array `redirect_uris` y el booleano `revoked`) con las relaciones FK correctas.
- Bug real encontrado solo en esa verificación en vivo (ningún test lo hubiera detectado): sustitución de variables de `psql` (`:'tenant_id'`) no confiable dentro de `\copy (...)`; corregido validando el UUID en bash e interpolándolo directamente. Ver `docs/ARQUITECTURA.md`.
- 174/174 tests automatizados en verde (169 previos + 5 nuevos de `TenantIsolationTest`).
- Documentación actualizada: `ARQUITECTURA.md` (sección completa del ticket 008), `README.md` (proceso paso a paso de clonado a instancia dedicada).
