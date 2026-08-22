package com.mcortes.authcoremc.service;

/**
 * A social login attempt that {@code SocialLoginSuccessHandler} (ticket 037)
 * must refuse for a business reason — not a technical/provider failure —
 * e.g. Facebook not sharing an email (HU-1), or the provider reporting an
 * unverified email that collides with an existing account (see the
 * Javadoc on {@link SocialLoginUserResolver#resolve} for why that case can't
 * be auto-linked). Always caught by the success handler and turned into a
 * themed redirect back to {@code /ui/login} with a clear, user-facing
 * message — never surfaced as a 500.
 */
public class SocialLoginBlockedException extends RuntimeException {

    /**
     * Stable, machine-readable reason — the query param
     * {@code SocialLoginSuccessHandler} redirects back to {@code /ui/login}
     * with, for ticket 039's UI to map to real (localized) copy. {@code
     * getMessage()} stays the English, developer-facing detail (logs/tests
     * only) — never shown to the end user directly, same separation the
     * rest of the project keeps between exception messages and user-facing
     * text.
     */
    private final String code;

    public SocialLoginBlockedException(String code, String message) {
        super(message);
        this.code = code;
    }

    public String getCode() {
        return code;
    }
}
