package com.mcortes.authcoremc.service;

/**
 * Thrown by {@link ChangePasswordService} when the caller's supplied
 * {@code currentPassword} doesn't match. Deliberately its own type, not a
 * reuse of {@link InvalidCredentialsException} — that one exists
 * specifically to avoid revealing which part of a LOGIN attempt (by an
 * anonymous caller) was wrong, an enumeration concern that doesn't apply
 * here: the caller is already authenticated and confirming their OWN
 * current password, so a specific message is safe and more useful.
 */
public class IncorrectCurrentPasswordException extends RuntimeException {

    public IncorrectCurrentPasswordException() {
        super("The current password is incorrect");
    }
}
