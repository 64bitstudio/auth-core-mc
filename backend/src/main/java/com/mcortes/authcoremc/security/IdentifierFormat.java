package com.mcortes.authcoremc.security;

import java.util.regex.Pattern;

/**
 * Format validation for the two identifiers a user can register with.
 * Intentionally simple (format only, not deliverability) — an email
 * verification link (ticket 003) or an OTP (ticket 005) is what actually
 * proves the address/number is real and reachable.
 */
public final class IdentifierFormat {

    // Simple, permissive email shape check — full RFC 5322 validation is
    // notoriously more trouble than it's worth; deliverability is proven by
    // actually sending a verification email (ticket 003), not by the regex.
    private static final Pattern EMAIL = Pattern.compile("^[^\\s@]+@[^\\s@]+\\.[^\\s@]+$");

    // E.164: a leading '+' followed by 8 to 15 digits.
    private static final Pattern PHONE = Pattern.compile("^\\+[1-9][0-9]{7,14}$");

    private IdentifierFormat() {}

    public static boolean isValidEmail(String email) {
        return email != null && EMAIL.matcher(email).matches();
    }

    public static boolean isValidPhone(String phone) {
        return phone != null && PHONE.matcher(phone).matches();
    }
}
