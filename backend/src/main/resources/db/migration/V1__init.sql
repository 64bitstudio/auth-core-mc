-- Ticket 001: base domain schema for auth-core-mc.
-- See docs/BASE_DE_DATOS.md for the full explanation of every table/column.

CREATE TABLE tenant (
    id                              UUID PRIMARY KEY,
    name                            TEXT NOT NULL,
    app_name                        TEXT NOT NULL,
    primary_color                   TEXT NOT NULL,
    access_token_ttl_seconds        INTEGER NOT NULL,
    refresh_token_ttl_seconds       INTEGER NOT NULL,
    email_verification_ttl_seconds  INTEGER NOT NULL,
    password_reset_ttl_seconds      INTEGER NOT NULL,
    otp_ttl_seconds                 INTEGER NOT NULL,
    created_at                      TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- Named app_user, not "user": USER is a reserved word in PostgreSQL/ANSI SQL.
CREATE TABLE app_user (
    id                      UUID PRIMARY KEY,
    tenant_id               UUID NOT NULL REFERENCES tenant (id),
    email                   TEXT,
    phone                   TEXT,
    nombre                  TEXT NOT NULL,
    apellidos               TEXT NOT NULL,
    password_hash           TEXT,
    email_verified          BOOLEAN NOT NULL DEFAULT false,
    phone_verified          BOOLEAN NOT NULL DEFAULT false,
    totp_secret_encrypted   TEXT,
    created_at              TIMESTAMPTZ NOT NULL DEFAULT now(),

    -- Defense in depth: the same rule is also enforced in Java (User's
    -- constructor) before ever reaching the database — see
    -- docs/ARQUITECTURA.md and User.java. Nothing that writes to this table
    -- directly (a bug, a future caller, a manual SQL fix) can bypass it.
    CONSTRAINT app_user_email_or_phone_required
        CHECK (email IS NOT NULL OR phone IS NOT NULL),

    -- NULLs are treated as distinct by Postgres, so any number of
    -- phone-only or email-only users can coexist with a NULL in the other
    -- column — only an actual duplicate value collides.
    CONSTRAINT app_user_tenant_email_unique UNIQUE (tenant_id, email),
    CONSTRAINT app_user_tenant_phone_unique UNIQUE (tenant_id, phone)
);

CREATE TABLE tenant_identity_provider (
    id                        UUID PRIMARY KEY,
    tenant_id                 UUID NOT NULL REFERENCES tenant (id),
    provider                  TEXT NOT NULL CHECK (provider IN ('GOOGLE', 'FACEBOOK', 'APPLE')),
    enabled                   BOOLEAN NOT NULL DEFAULT false,
    client_id                 TEXT,
    client_secret_encrypted   TEXT,

    CONSTRAINT tenant_identity_provider_unique UNIQUE (tenant_id, provider)
);

-- Named identity_client, not oauth2_client: avoids clashing with Spring
-- Authorization Server's own default oauth2_registered_client schema,
-- which ticket 007 will reconcile this table against.
CREATE TABLE identity_client (
    id                  UUID PRIMARY KEY,
    tenant_id           UUID NOT NULL REFERENCES tenant (id),
    client_id           TEXT NOT NULL UNIQUE,
    client_secret_hash  TEXT,
    is_first_party      BOOLEAN NOT NULL DEFAULT false,
    redirect_uris       TEXT[]
);

CREATE TABLE refresh_token (
    id           UUID PRIMARY KEY,
    user_id      UUID NOT NULL REFERENCES app_user (id),
    client_id    UUID NOT NULL REFERENCES identity_client (id),
    token_hash   TEXT NOT NULL UNIQUE,
    revoked      BOOLEAN NOT NULL DEFAULT false,
    expires_at   TIMESTAMPTZ NOT NULL
);
