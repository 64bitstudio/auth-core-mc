package com.mcortes.authcoremc.service;

/**
 * Thrown by {@link SetPasswordService} when the authenticated account
 * already has a {@code password_hash} — HU-5 (ticket 041) only ever lets a
 * social-only account set its FIRST password; an account that already has
 * one must go through the existing forgot-password flow
 * ({@link PasswordResetService}) instead, never silently overwritten here.
 */
public class PasswordAlreadySetException extends RuntimeException {

    public PasswordAlreadySetException() {
        super("This account already has a password. Use the password reset flow to change it.");
    }
}
