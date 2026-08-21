-- Ticket 011: admin-panel role (platform_admin / tenant_admin) for the
-- forthcoming admin panel. Default 'NONE' — every existing row (all of
-- them regular end users, not panel admins) keeps working unchanged.
-- New file, not an edit to V1/V2 — Flyway migrations are immutable once applied.

ALTER TABLE app_user
    ADD COLUMN role TEXT NOT NULL DEFAULT 'NONE'
        CHECK (role IN ('NONE', 'TENANT_ADMIN', 'PLATFORM_ADMIN'));
