-- Ticket 017: Vault-wrapped AES-256 data-key per tenant, for envelope
-- encryption of secrets belonging to that tenant's own OAuth providers.
-- Nullable — generated lazily on first use (TenantSecretEncryptor), no
-- backfill needed for tenants that predate this ticket.
-- New file, not an edit to V1/V2/V3 — Flyway migrations are immutable once applied.

ALTER TABLE tenant
    ADD COLUMN wrapped_data_key TEXT;
