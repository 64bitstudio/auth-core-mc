# 017 — Cifrado por sobres para secretos de clientes (KMS)

## Objetivo
Reemplazar la clave única estática de `SecretEncryptor` por cifrado por sobres: cada tenant obtiene su propia data-key, cifrada por una master key en KMS — si una data-key se filtra, el impacto queda acotado a un solo tenant. Nace de `docs/definiciones/panel-administracion-clientes.md` (Riesgos y decisiones — gestión de la clave de cifrado).

## Criterios de aceptación (TDD)
- Cada `TENANT` obtiene un `kms_data_key_id` al crearse.
- `SecretEncryptor` se extiende para resolver la data-key correcta por tenant, en vez de la clave estática única actual.
- Los 2 secretos ya cifrados (credenciales propias de Google/Meta) se migran al nuevo esquema sin pérdida ni tiempo de inactividad.
- Proveedor de KMS específico (AWS KMS / GCP KMS / HashiCorp Vault) confirmado y documentado en `docs/ARQUITECTURA.md` antes de implementar — pendiente de definir según dónde se despliegue en producción (pregunta abierta heredada de la definición).

## Hecho
