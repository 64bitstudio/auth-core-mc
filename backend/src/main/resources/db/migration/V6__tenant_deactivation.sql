-- Ticket 013: alta/edición/baja de tenants desde el panel de administración.
-- deactivated_at nullable — soft delete, dispara la purga automática a 90
-- días (TenantPurgeService). name único: es el identificador estable del
-- tenant en el panel (no hay un campo "slug" separado — decisión deliberada,
-- evita romper la firma del constructor de Tenant usada en decenas de
-- tests ya existentes de tickets anteriores).
-- New file, not an edit to V1-V5 — Flyway migrations are immutable once applied.

ALTER TABLE tenant
    ADD COLUMN deactivated_at TIMESTAMPTZ,
    ADD CONSTRAINT tenant_name_unique UNIQUE (name);
