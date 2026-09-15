-- Ticket 062: "Sesiones activas" (Mi Perfil, galgoth-studio) -- dispositivo/
-- navegador (parseado del user_agent en el momento de leer, no guardado ya
-- parseado) + última actividad. Sin geolocalización de IP (decisión de
-- Marco). DEFAULT now() en created_at/last_used_at para que las filas ya
-- existentes (emitidas antes de esta migración) no queden con timestamps
-- nulos.
ALTER TABLE refresh_token ADD COLUMN user_agent TEXT;
ALTER TABLE refresh_token ADD COLUMN created_at TIMESTAMPTZ NOT NULL DEFAULT now();
ALTER TABLE refresh_token ADD COLUMN last_used_at TIMESTAMPTZ NOT NULL DEFAULT now();
