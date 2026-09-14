-- Ticket 060: campos de perfil (Mi Perfil, galgoth-studio) -- datos de
-- identidad de la persona, viven aquí, no en galgoth-studio (a diferencia
-- de owner_display_name/preferencias, que sí son dato de producto).
ALTER TABLE app_user ADD COLUMN country VARCHAR(100);
ALTER TABLE app_user ADD COLUMN username VARCHAR(50);
ALTER TABLE app_user ADD CONSTRAINT app_user_tenant_username_unique UNIQUE (tenant_id, username);
