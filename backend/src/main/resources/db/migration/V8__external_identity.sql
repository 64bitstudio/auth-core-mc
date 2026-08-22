-- Ticket 035: primer ticket de la épica de login social real
-- (docs/definiciones/login-social-real.md, Diseño técnico, decisión 6).
-- Vincula un app_user con su identidad en un proveedor externo
-- (Google/Facebook) — tabla hermana de tenant_identity_provider, mismo
-- patrón de tenant_id denormalizado que login_event/tenant_identity_provider.
--
-- provider_user_id guarda el `sub` (Google) / `id` (Facebook) del
-- proveedor — nunca el email, que puede cambiar del lado del proveedor.
--
-- Dos constraints de unicidad:
--  - (tenant_id, provider, provider_user_id): la misma cuenta social no
--    puede vincularse dos veces dentro del mismo tenant.
--  - (user_id, provider): un app_user no puede tener más de un vínculo con
--    el mismo proveedor, pero sí puede vincular proveedores distintos
--    (Decisión 6, docs/definiciones/login-social-real.md).
--
-- New file, not an edit to V1-V7 — Flyway migrations are immutable once
-- applied.

CREATE TABLE external_identity (
    id                UUID PRIMARY KEY,
    tenant_id         UUID NOT NULL REFERENCES tenant (id),
    user_id           UUID NOT NULL REFERENCES app_user (id),
    provider          TEXT NOT NULL CHECK (provider IN ('GOOGLE', 'FACEBOOK', 'APPLE')),
    provider_user_id  TEXT NOT NULL,
    linked_at         TIMESTAMPTZ NOT NULL DEFAULT now(),

    CONSTRAINT external_identity_tenant_provider_unique UNIQUE (tenant_id, provider, provider_user_id),
    CONSTRAINT external_identity_user_provider_unique UNIQUE (user_id, provider)
);
