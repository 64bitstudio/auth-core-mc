-- Soporte para clientes machine-to-machine (grant client_credentials) —
-- pedido por mail-core-mc (ver su docs/definiciones/mail-core-mc-v1.md,
-- HU-6) para autenticar llamadas app-a-app sin un usuario humano de por
-- medio, algo que este servicio no tenía hasta ahora (solo
-- Authorization Code + PKCE).
--
-- Puramente aditivo: valores default preservan el comportamiento actual
-- de TODOS los identity_client existentes (is_machine_client=false,
-- scopes=openid+profile, exactamente lo que TenantAwareRegisteredClientRepository
-- ya hardcodeaba) — cero cambio de comportamiento para clientes existentes.
ALTER TABLE identity_client
    ADD COLUMN is_machine_client BOOLEAN NOT NULL DEFAULT false,
    ADD COLUMN scopes TEXT[] NOT NULL DEFAULT ARRAY['openid', 'profile'];
