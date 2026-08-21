-- Ticket 018: mecanismo de break-glass — auditoría fuerte de cada uso
-- (éxito o fallo de autenticación), independiente del resto del esquema.
--
-- target_tenant_id deliberadamente SIN foreign key a tenant: este es un
-- registro de auditoría permanente que debe sobrevivir aunque el tenant
-- referenciado se purgue después (TenantPurgeService, ticket 013) — un FK
-- forzaría borrar/anular filas de auditoría cuando un tenant desaparece,
-- justo lo contrario de lo que un rastro de auditoría de un mecanismo de
-- alto privilegio necesita.
-- New file, not an edit to V1-V6 — Flyway migrations are immutable once applied.

CREATE TABLE break_glass_audit_event (
    id               UUID PRIMARY KEY,
    occurred_at      TIMESTAMPTZ NOT NULL,
    operator         TEXT NOT NULL,
    remote_ip        TEXT NOT NULL,
    action           TEXT NOT NULL CHECK (action IN ('DIAGNOSTICS', 'DEACTIVATE_TENANT')),
    target_tenant_id UUID,
    outcome          TEXT NOT NULL CHECK (outcome IN ('SUCCESS', 'FAILURE')),
    detail           TEXT
);

CREATE INDEX break_glass_audit_event_occurred_at_idx ON break_glass_audit_event (occurred_at);
