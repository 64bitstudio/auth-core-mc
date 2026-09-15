-- Ticket 064 ("Mi Perfil", galgoth-studio -- eliminar cuenta): mismo patrón
-- que V6__tenant_deactivation.sql -- soft delete, deactivated_at nullable,
-- null significa activo. A diferencia de Tenant, este ticket no incluye
-- ninguna purga física automática (fuera de alcance, ver el propio ticket).
-- New file, not an edit to V1-V13 -- Flyway migrations are immutable once applied.

ALTER TABLE app_user
    ADD COLUMN deactivated_at TIMESTAMPTZ;
