-- Ticket 070: geolocalización de "Sesiones activas" -- revierte la decisión
-- explícita del ticket 062 ("Sin geolocalización de IP, decisión de
-- Marco") al alinear la Pantalla Usuario con su mockup original
-- (galgoth-studio#079), que sí muestra ciudad por sesión. Solo la IP se
-- guarda aquí; la resolución a ciudad/país se hace en caliente al leer
-- (GeoIpService + GeoLite2), nunca guardada -- así una actualización de la
-- base de datos de MaxMind no deja ciudades viejas colgando en filas ya
-- existentes.
ALTER TABLE refresh_token ADD COLUMN client_ip TEXT;
