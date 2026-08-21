# 017 — Cifrado por sobres para secretos de clientes (KMS)

## Objetivo
Reemplazar la clave única estática de `SecretEncryptor` por cifrado por sobres: cada tenant obtiene su propia data-key, cifrada por una master key en KMS — si una data-key se filtra, el impacto queda acotado a un solo tenant. Nace de `docs/definiciones/panel-administracion-clientes.md` (Riesgos y decisiones — gestión de la clave de cifrado).

## Criterios de aceptación (TDD)
- Cada `TENANT` obtiene un `kms_data_key_id` al crearse.
- `SecretEncryptor` se extiende para resolver la data-key correcta por tenant, en vez de la clave estática única actual.
- Los 2 secretos ya cifrados (credenciales propias de Google/Meta) se migran al nuevo esquema sin pérdida ni tiempo de inactividad.
- Proveedor de KMS específico (AWS KMS / GCP KMS / HashiCorp Vault) confirmado y documentado en `docs/ARQUITECTURA.md` antes de implementar — pendiente de definir según dónde se despliegue en producción (pregunta abierta heredada de la definición).

## Hecho (TDD real: rojo → verde)
- Vault instalado en `~/dev-infra` (junto a SonarQube) — backend de archivo persistente, motor Transit habilitado, clave `auth-core-mc-tenant-keys` creada. 2 bugs reales encontrados y arreglados en vivo (ver `docs/ARQUITECTURA.md`): duplicación de `-config` en el entrypoint oficial de la imagen, y permisos del volumen (`chown vault:vault`).
- `VaultTransitEncryptor` (llamada HTTP delgada, mismo patrón que `ResendEmailSender`) + `TenantSecretEncryptor` (AES-256-GCM local con la data-key desenvuelta) implementan el cifrado por sobres real.
- `Tenant.wrappedDataKey` (columna nueva, nullable, migración V4) — se genera perezosamente en el primer secreto configurado, sin backfill.
- `TenantIdentityProviderService` migrado de `SecretEncryptor` (clave única) a `TenantSecretEncryptor` (por tenant).
- **Verificado que no hacía falta migrar datos reales**: se consultó la BD local real, `tenant_identity_provider` tiene 0 filas — nada que migrar todavía.
- Tests con Vault real vía Testcontainers (`testcontainers-vault`), no contra el Vault compartido.
- 201/201 tests en verde (196 previos + 5 nuevos).
- Proveedor de KMS confirmado con el Product Owner: HashiCorp Vault self-hosted.
