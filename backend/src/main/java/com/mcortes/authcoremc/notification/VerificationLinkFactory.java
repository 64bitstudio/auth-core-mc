package com.mcortes.authcoremc.notification;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Builds the clickable link sent in verification/change-email/reset emails.
 * {@code app.base-url} is a single global value for now — per-tenant custom
 * domains/base URLs are a UI/theming concern that ticket 009 may extend
 * this with, not something ticket 003 needs to solve.
 */
@Component
public class VerificationLinkFactory {

    private final String baseUrl;

    public VerificationLinkFactory(@Value("${app.base-url:http://localhost:8080}") String baseUrl) {
        this.baseUrl = baseUrl;
    }

    /** @param path e.g. "/api/v1/verify-email/confirm" — the endpoint that will consume the token. */
    public String build(String path, String token) {
        return baseUrl + path + "?token=" + token;
    }
}
