package com.mcortes.authcoremc.security;

import com.mcortes.authcoremc.service.WeakPasswordException;
import java.util.regex.Pattern;

/**
 * Minimum password strength rule: at least 8 characters, at least one
 * letter and one digit. Deliberately simple for now — a starting baseline,
 * not a claim of completeness; tighten here if a future requirement demands
 * more (e.g. a breached-password check).
 */
public final class PasswordPolicy {

    private static final int MIN_LENGTH = 8;
    private static final Pattern HAS_LETTER = Pattern.compile("[A-Za-z]");
    private static final Pattern HAS_DIGIT = Pattern.compile("[0-9]");

    private PasswordPolicy() {}

    public static void validate(String rawPassword) {
        if (rawPassword == null || rawPassword.length() < MIN_LENGTH) {
            throw new WeakPasswordException("Password must be at least " + MIN_LENGTH + " characters long");
        }
        if (!HAS_LETTER.matcher(rawPassword).find() || !HAS_DIGIT.matcher(rawPassword).find()) {
            throw new WeakPasswordException("Password must contain at least one letter and one digit");
        }
    }
}
