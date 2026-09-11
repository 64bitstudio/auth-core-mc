-- Ticket 055: permite que un identity_client con UI propia (ej.
-- galgoth-studio) reciba el rebote final del login social en su propio
-- redirect_uri en vez de en /ui/social-callback de auth-core-mc.
--
-- Puramente aditivo: default false preserva el comportamiento actual de
-- TODOS los identity_client existentes (siguen aterrizando en las
-- páginas hospedadas por auth-core-mc, /ui/social-callback y /ui/login)
-- — cero cambio de comportamiento para clientes existentes.
ALTER TABLE identity_client
    ADD COLUMN hosts_own_login_ui BOOLEAN NOT NULL DEFAULT false;
