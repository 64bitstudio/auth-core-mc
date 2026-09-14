-- Ticket 058: theming de correo por tenant -- todo nullable/aditivo, un
-- tenant sin estos campos sigue usando la plantilla genérica del ticket
-- 057 (solo app_name/primary_color). Solo galgoth-studio los tendrá
-- poblados por ahora.
ALTER TABLE tenant ADD COLUMN email_logo_url VARCHAR(500);
ALTER TABLE tenant ADD COLUMN email_hero_image_url VARCHAR(500);
ALTER TABLE tenant ADD COLUMN email_side_image_url VARCHAR(500);
ALTER TABLE tenant ADD COLUMN email_header_subtitle VARCHAR(200);
ALTER TABLE tenant ADD COLUMN email_header_tagline VARCHAR(500);
ALTER TABLE tenant ADD COLUMN email_footer_tagline VARCHAR(200);
ALTER TABLE tenant ADD COLUMN email_discord_url VARCHAR(500);
ALTER TABLE tenant ADD COLUMN email_youtube_url VARCHAR(500);
ALTER TABLE tenant ADD COLUMN email_twitter_url VARCHAR(500);
ALTER TABLE tenant ADD COLUMN email_github_url VARCHAR(500);
