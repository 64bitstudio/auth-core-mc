-- Ticket 005: which 2FA method (if any) a user has chosen as their preferred one.
-- New file, not an edit to V1 — Flyway migrations are immutable once applied.

ALTER TABLE app_user
    ADD COLUMN two_factor_method TEXT NOT NULL DEFAULT 'NONE'
        CHECK (two_factor_method IN ('NONE', 'OTP_EMAIL', 'OTP_SMS', 'TOTP'));
