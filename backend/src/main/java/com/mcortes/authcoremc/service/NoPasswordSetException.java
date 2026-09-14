package com.mcortes.authcoremc.service;

/**
 * Thrown by {@link ChangePasswordService} when the authenticated account
 * has no {@code password_hash} yet (social-only) — "change" only makes
 * sense once a password already exists to confirm. The account should use
 * {@code POST /api/v1/account/password} ({@link SetPasswordService})
 * instead — the mirror image of {@link PasswordAlreadySetException}.
 */
public class NoPasswordSetException extends RuntimeException {

    public NoPasswordSetException() {
        super("This account has no password yet. Use the set-password endpoint instead.");
    }
}
